package com.jormlong.capture;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Orchestrates one recording: a video process (backend per strategy) plus an
 * FFmpeg audio process, written to hidden temp files in the output directory
 * and muxed with {@code -c copy} on stop.
 *
 * <p>The audio process starts only once the video temp file becomes non-empty:
 * on portal backends video begins when the user accepts the OS share dialog,
 * so starting audio immediately would make it lead by seconds.
 *
 * <p>{@code stopping} marks an intentional shutdown so process-exit watchers
 * stay quiet; {@code finished} is CAS-guarded so exactly one of {user stop,
 * async failure} finalizes the session.
 */
public final class RecordingSession {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final long AUDIO_START_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(90);

    private final RecordingConfig cfg;
    private final Consumer<String> onAsyncFailure; // invoked on a background thread

    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);

    private volatile Process videoProc;
    private volatile Process audioProc;
    private Path videoTmp;
    private Path audioTmp;
    private Path videoLog;
    private Path audioLog;
    private String stamp;

    public RecordingSession(RecordingConfig cfg, Consumer<String> onAsyncFailure) {
        this.cfg = cfg;
        this.onAsyncFailure = onAsyncFailure;
    }

    public void start() throws IOException {
        stamp = LocalDateTime.now().format(STAMP);
        Files.createDirectories(cfg.outputDir());
        // Dot-file temps in the output dir keep the final move same-filesystem.
        videoTmp = cfg.outputDir().resolve(".jormlong-" + stamp + ".video.mp4");
        audioTmp = cfg.outputDir().resolve(".jormlong-" + stamp + ".audio.m4a");
        videoLog = Files.createTempFile("jormlong-video-", ".log");
        audioLog = Files.createTempFile("jormlong-audio-", ".log");

        videoProc = launch(videoCommand(), videoLog);
        watch(videoProc, cfg.strategy().displayName() + " (video)", videoLog);

        Thread audioStarter = new Thread(this::startAudioWhenVideoBegins, "jormlong-audio-start");
        audioStarter.setDaemon(true);
        audioStarter.start();
    }

    public boolean isRunning() {
        Process v = videoProc;
        return v != null && !finished.get() && !stopping.get();
    }

    /**
     * Stops both processes gracefully, muxes, deletes temps. Blocking — call
     * from a worker thread. Returns null if the async failure path already
     * finalized (its callback carries the error).
     */
    public Path stopAndFinalize() throws Exception {
        stopping.set(true);
        Process audio;
        synchronized (this) {
            // startAudioWhenVideoBegins launches inside this lock after
            // re-checking stopping, so past this point audioProc is settled.
            audio = audioProc;
        }
        Process video = videoProc;

        signalStop(audio, true);
        signalStop(video, cfg.strategy().stopsWithQ());
        awaitExit(audio);
        awaitExit(video);

        if (!finished.compareAndSet(false, true)) {
            return null; // failure path won the race and already cleaned up
        }
        try {
            Path output = cfg.outputDir().resolve("Recording_" + stamp + ".mp4");
            if (Files.exists(audioTmp) && Files.size(audioTmp) > 0) {
                mux(output);
            } else {
                if (!Files.exists(videoTmp) || Files.size(videoTmp) == 0) {
                    throw new IOException("No video was captured.\n\nLast video output:\n" + tail(videoLog));
                }
                Files.move(videoTmp, output, StandardCopyOption.REPLACE_EXISTING);
            }
            return output;
        } finally {
            cleanupTemps();
            cleanupLogs();
        }
    }

    // ---- process launch & supervision ----

    private void watch(Process proc, String what, Path log) {
        proc.onExit().thenAccept(p -> {
            if (stopping.get() || p.exitValue() == 0) {
                return;
            }
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            stopping.set(true);
            Process sibling = (p == videoProc) ? audioProc : videoProc;
            boolean siblingIsFfmpeg = (p == videoProc) || cfg.strategy().stopsWithQ();
            signalStop(sibling, siblingIsFfmpeg);
            awaitExit(sibling);
            String msg = what + " exited unexpectedly with code " + p.exitValue()
                    + ".\n\nLast output:\n" + tail(log);
            cleanupTemps();
            cleanupLogs();
            onAsyncFailure.accept(msg);
        });
    }

    private void startAudioWhenVideoBegins() {
        long deadline = System.nanoTime() + AUDIO_START_BUDGET_NANOS;
        while (System.nanoTime() < deadline) {
            if (stopping.get() || finished.get()) {
                return;
            }
            boolean videoBegan = false;
            try {
                videoBegan = Files.exists(videoTmp) && Files.size(videoTmp) > 0;
            } catch (IOException ignored) {
                // size() raced a deletion; poll again
            }
            if (videoBegan) {
                try {
                    synchronized (this) {
                        if (stopping.get() || finished.get()) {
                            return;
                        }
                        audioProc = launch(audioCommand(), audioLog);
                        watch(audioProc, "ffmpeg (audio)", audioLog);
                    }
                } catch (IOException e) {
                    if (finished.compareAndSet(false, true)) {
                        stopping.set(true);
                        signalStop(videoProc, cfg.strategy().stopsWithQ());
                        awaitExit(videoProc);
                        cleanupTemps();
                        cleanupLogs();
                        onAsyncFailure.accept("Could not start audio capture: " + e.getMessage());
                    }
                }
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!stopping.get() && finished.compareAndSet(false, true)) {
            stopping.set(true);
            signalStop(videoProc, cfg.strategy().stopsWithQ());
            awaitExit(videoProc);
            String msg = "Video capture never started (no data written within 90 seconds)."
                    + "\n\nLast output:\n" + tail(videoLog);
            cleanupTemps();
            cleanupLogs();
            onAsyncFailure.accept(msg);
        }
    }

    private static Process launch(List<String> command, Path log) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());
        // stdin stays a pipe so FFmpeg processes can be stopped with 'q'
        return pb.start();
    }

    /** Ask a process to finish and flush its output: 'q' for FFmpeg, SIGINT otherwise. */
    private static void signalStop(Process proc, boolean ffmpegQ) {
        if (proc == null || !proc.isAlive()) {
            return;
        }
        if (ffmpegQ) {
            try {
                OutputStream stdin = proc.getOutputStream();
                stdin.write('q');
                stdin.flush();
            } catch (IOException ignored) {
                // stdin already closed; escalation in awaitExit will handle it
            }
        } else {
            // Java offers no SIGINT; recorder backends finalize their file on it
            try {
                new ProcessBuilder("kill", "-INT", Long.toString(proc.pid()))
                        .start().waitFor(2, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** Graceful escalation: 10s after the stop signal, SIGTERM, 3s, then SIGKILL. */
    private static void awaitExit(Process proc) {
        if (proc == null) {
            return;
        }
        try {
            if (proc.waitFor(10, TimeUnit.SECONDS)) {
                return;
            }
            proc.destroy();
            if (proc.waitFor(3, TimeUnit.SECONDS)) {
                return;
            }
            proc.destroyForcibly();
            proc.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
        }
    }

    // ---- command lines ----

    private List<String> videoCommand() {
        String out = videoTmp.toString();
        return switch (cfg.strategy()) {
            case X11GRAB -> List.of("ffmpeg", "-y", "-hide_banner", "-nostats",
                    "-f", "x11grab",
                    "-framerate", Integer.toString(cfg.fps()),
                    "-video_size", cfg.width() + "x" + cfg.height(),
                    "-i", cfg.display(),
                    "-c:v", "libx264", "-preset", "veryfast",
                    "-crf", x264Crf(), "-pix_fmt", "yuv420p",
                    out);
            case GPU_SCREEN_RECORDER -> List.of("gpu-screen-recorder",
                    "-w", "portal", "-f", Integer.toString(cfg.fps()),
                    "-q", switch (cfg.quality()) {
                        case MEDIUM -> "medium";
                        case HIGH -> "very_high";
                        case ULTRA -> "ultra";
                    },
                    "-c", "mp4", "-o", out);
            case WL_SCREENREC -> {
                List<String> cmd = new ArrayList<>(List.of("wl-screenrec"));
                switch (cfg.quality()) {
                    case MEDIUM -> { } // tool default (5 Mbps)
                    case HIGH -> cmd.addAll(List.of("--bitrate", "12 MB"));
                    case ULTRA -> cmd.addAll(List.of("--bitrate", "20 MB"));
                }
                cmd.addAll(List.of("-f", out));
                yield cmd;
            }
            case WF_RECORDER -> List.of("wf-recorder",
                    "--framerate", Integer.toString(cfg.fps()),
                    "-p", "crf=" + x264Crf(), "-f", out);
        };
    }

    private String x264Crf() {
        return switch (cfg.quality()) {
            case MEDIUM -> "23";
            case HIGH -> "18";
            case ULTRA -> "15";
        };
    }

    private List<String> audioCommand() {
        List<String> cmd = new ArrayList<>(List.of(
                "ffmpeg", "-y", "-hide_banner", "-nostats",
                "-f", "pulse", "-i", cfg.micSource()));
        if (cfg.monitorSource() != null) {
            cmd.addAll(List.of("-f", "pulse", "-i", cfg.monitorSource(),
                    "-filter_complex", "amix=inputs=2:duration=longest"));
        }
        cmd.addAll(List.of("-c:a", "aac", "-b:a", "160k", audioTmp.toString()));
        return cmd;
    }

    private void mux(Path output) throws Exception {
        Path muxLog = Files.createTempFile("jormlong-mux-", ".log");
        try {
            Process p = launch(List.of("ffmpeg", "-y", "-hide_banner", "-nostats",
                    "-i", videoTmp.toString(), "-i", audioTmp.toString(),
                    "-map", "0:v:0", "-map", "1:a:0", "-c", "copy",
                    output.toString()), muxLog);
            p.getOutputStream().close();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("Muxing timed out.\n\nLast output:\n" + tail(muxLog));
            }
            if (p.exitValue() != 0) {
                throw new IOException("Muxing failed (exit " + p.exitValue()
                        + ").\n\nLast output:\n" + tail(muxLog));
            }
        } finally {
            Files.deleteIfExists(muxLog);
        }
    }

    // ---- cleanup & diagnostics ----

    private void cleanupTemps() {
        deleteQuietly(videoTmp);
        deleteQuietly(audioTmp);
    }

    private void cleanupLogs() {
        deleteQuietly(videoLog);
        deleteQuietly(audioLog);
    }

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** Last ~15 lines of a log, reading at most the final 64 KB. */
    static String tail(Path log) {
        try (RandomAccessFile raf = new RandomAccessFile(log.toFile(), "r")) {
            long len = raf.length();
            int max = 64 * 1024;
            long from = Math.max(0, len - max);
            byte[] buf = new byte[(int) (len - from)];
            raf.seek(from);
            raf.readFully(buf);
            String text = new String(buf, StandardCharsets.UTF_8);
            String[] lines = text.split("\n");
            int keep = Math.min(15, lines.length);
            return String.join("\n", java.util.Arrays.asList(lines).subList(lines.length - keep, lines.length)).trim();
        } catch (IOException e) {
            return "(no log available)";
        }
    }
}
