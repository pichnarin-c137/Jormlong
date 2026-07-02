package com.jormlong.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Runs short-lived external commands and probes PATH. Never throws. */
public final class Cmd {

    public record Result(int exit, String stdout, String stderr) {
        public boolean ok() {
            return exit == 0;
        }
    }

    private Cmd() {
    }

    public static Result run(int timeoutSec, String... command) {
        try {
            Process p = new ProcessBuilder(command).start();
            p.getOutputStream().close();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread outReader = drain(p.getInputStream(), out);
            Thread errReader = drain(p.getErrorStream(), err);
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                p.waitFor(2, TimeUnit.SECONDS);
            }
            outReader.join(1000);
            errReader.join(1000);
            if (!done) {
                return new Result(-1, out.toString(),
                        "timed out after " + timeoutSec + "s: " + String.join(" ", command));
            }
            return new Result(p.exitValue(), out.toString(), err.toString());
        } catch (IOException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Result(-1, "", msg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "", "interrupted");
        }
    }

    private static Thread drain(InputStream in, StringBuilder into) {
        Thread t = new Thread(() -> {
            try (in) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    into.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // process torn down while reading; keep what we have
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    public static boolean onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            File f = new File(dir, name);
            if (f.isFile() && f.canExecute()) {
                return true;
            }
        }
        return false;
    }
}
