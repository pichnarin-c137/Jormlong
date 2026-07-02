package com.jormlong.preflight;

import com.jormlong.preflight.Environment.DesktopFamily;
import com.jormlong.preflight.Environment.SessionType;
import com.jormlong.util.Cmd;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Runs the five environment checks. Blocking — call off the FX thread. */
public final class Preflight {

    public record Report(List<CheckResult> checks, Environment environment, boolean ready) {
    }

    private Preflight() {
    }

    public static Report run() {
        List<CheckResult> checks = new ArrayList<>();

        // 1. Session type
        SessionType session = detectSession();
        checks.add(switch (session) {
            case X11 -> new CheckResult("Display session", true, true, "X11 session detected.", null);
            case WAYLAND -> new CheckResult("Display session", true, true, "Wayland session detected.", null);
            case UNKNOWN -> new CheckResult("Display session", false, true,
                    "Could not detect an X11 or Wayland session.",
                    "log into an X11 or Wayland session");
        });

        // 2. Desktop (informational)
        String desktopRaw = orEmpty(System.getenv("XDG_CURRENT_DESKTOP"));
        DesktopFamily family = classifyDesktop(desktopRaw);
        String desktopMsg = desktopRaw.isEmpty() ? "No desktop reported (XDG_CURRENT_DESKTOP unset)."
                : "Desktop: " + desktopRaw + " (" + switch (family) {
                    case PORTAL -> "uses the xdg-desktop-portal capture flow";
                    case WLROOTS -> "wlroots-based compositor";
                    case OTHER -> "no special handling";
                } + ").";
        checks.add(new CheckResult("Desktop environment", true, false, desktopMsg, null));

        // 3. FFmpeg
        Cmd.Result ff = Cmd.run(10, "ffmpeg", "-version");
        String ffmpegVersion = ff.ok() ? parseFfmpegVersion(ff.stdout()) : null;
        checks.add(ff.ok()
                ? new CheckResult("FFmpeg", true, true, "FFmpeg " + ffmpegVersion + " found.", null)
                : new CheckResult("FFmpeg", false, true,
                        "FFmpeg is required for audio capture and muxing (and for X11 video capture).",
                        "sudo apt install ffmpeg"));

        // 4. Capture backend
        boolean hasGsr = Cmd.onPath("gpu-screen-recorder");
        boolean hasWls = Cmd.onPath("wl-screenrec");
        boolean hasWfr = Cmd.onPath("wf-recorder");
        checks.add(backendCheck(session, family, ff.ok(), hasGsr, hasWls, hasWfr));

        // 5. Audio sources
        List<AudioSource> sources = new ArrayList<>();
        Cmd.Result pactl = Cmd.run(10, "pactl", "list", "sources", "short");
        if (pactl.ok()) {
            for (String line : pactl.stdout().split("\n")) {
                String[] cols = line.split("\t");
                if (cols.length >= 2 && !cols[0].isBlank()) {
                    sources.add(new AudioSource(cols[0].trim(), cols[1].trim()));
                }
            }
        }
        if (!sources.isEmpty()) {
            long monitors = sources.stream().filter(AudioSource::isMonitor).count();
            checks.add(new CheckResult("Audio sources", true, true,
                    sources.size() + " source(s) found (" + monitors + " system-output monitor(s)).", null));
        } else if (!Cmd.onPath("pactl")) {
            checks.add(new CheckResult("Audio sources", false, true,
                    "pactl not found — it is needed to list microphones and system-audio monitors.",
                    "sudo apt install pulseaudio-utils"));
        } else {
            checks.add(new CheckResult("Audio sources", false, true,
                    "No audio sources reported. Start PipeWire/PulseAudio and re-check.", null));
        }

        Environment env = new Environment(session, desktopRaw, family, ffmpegVersion,
                hasGsr, hasWls, hasWfr, List.copyOf(sources));
        boolean ready = checks.stream().allMatch(c -> c.ok() || !c.essential());
        return new Report(List.copyOf(checks), env, ready);
    }

    private static SessionType detectSession() {
        String t = orEmpty(System.getenv("XDG_SESSION_TYPE")).toLowerCase(Locale.ROOT);
        if (t.contains("wayland")) {
            return SessionType.WAYLAND;
        }
        if (t.contains("x11")) {
            return SessionType.X11;
        }
        if (System.getenv("WAYLAND_DISPLAY") != null) {
            return SessionType.WAYLAND;
        }
        if (System.getenv("DISPLAY") != null) {
            return SessionType.X11;
        }
        return SessionType.UNKNOWN;
    }

    private static DesktopFamily classifyDesktop(String desktopRaw) {
        String d = desktopRaw.toLowerCase(Locale.ROOT);
        if (d.contains("gnome") || d.contains("kde")) {
            return DesktopFamily.PORTAL;
        }
        if (d.contains("sway") || d.contains("hyprland") || d.contains("river") || d.contains("wayfire")) {
            return DesktopFamily.WLROOTS;
        }
        return DesktopFamily.OTHER;
    }

    private static CheckResult backendCheck(SessionType session, DesktopFamily family,
        boolean ffmpegOk, boolean hasGsr, boolean hasWls, boolean hasWfr) {
        String label = "Capture backend";
        return switch (session) {
            case X11 -> ffmpegOk
                    ? new CheckResult(label, true, true,
                            "FFmpeg x11grab will be used — no extra backend needed on X11.", null)
                    : new CheckResult(label, false, true,
                            "X11 capture uses FFmpeg, which is missing (see the FFmpeg check above).",
                            "sudo apt install ffmpeg");
            case WAYLAND -> {
                if (family == DesktopFamily.WLROOTS) {
                    if (hasWfr) {
                        yield new CheckResult(label, true, true, "wf-recorder found.", null);
                    }
                    if (hasWls) {
                        yield new CheckResult(label, true, true, "wl-screenrec found.", null);
                    }
                    yield new CheckResult(label, false, true,
                            "No Wayland capture tool found. Install wf-recorder (or wl-screenrec).",
                            "sudo apt install wf-recorder");
                }
                if (hasGsr) {
                    yield new CheckResult(label, true, true,
                            "gpu-screen-recorder found (records via the desktop portal).", null);
                }
                if (hasWls) {
                    yield new CheckResult(label, true, true, "wl-screenrec found.", null);
                }
                yield new CheckResult(label, false, true,
                        "No Wayland capture tool found. gpu-screen-recorder is recommended on this desktop; "
                                + "wl-screenrec can be installed with the command below.",
                        "sudo apt install wl-screenrec");
            }
            case UNKNOWN -> new CheckResult(label, false, true,
                    "Cannot pick a capture backend without knowing the session type.", null);
        };
    }

    private static String parseFfmpegVersion(String stdout) {
        String first = stdout.split("\n", 2)[0].trim();
        // "ffmpeg version 6.1.1-3ubuntu5 Copyright ..."
        String[] words = first.split("\\s+");
        return words.length >= 3 && first.startsWith("ffmpeg version") ? words[2] : first;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
