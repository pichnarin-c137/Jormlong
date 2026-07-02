package com.jormlong.capture;

import java.nio.file.Path;

/**
 * Everything a recording needs, resolved before start. {@code micSource} is
 * the primary audio input (mic or system-output monitor; null → silent
 * video); {@code monitorSource} is a second input mixed in via amix, or null.
 * Width/height are physical pixels, already floored to even (yuv420p
 * requirement), and only used by x11grab.
 */
public record RecordingConfig(
        CaptureStrategy strategy,
        String display,
        int width,
        int height,
        int fps,
        Quality quality,
        String micSource,
        String monitorSource,
        Path outputDir) {
}
