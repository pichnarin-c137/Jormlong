package com.jormlong.capture;

import java.nio.file.Path;

/**
 * Everything a recording needs, resolved before start. {@code monitorSource}
 * is null when system audio is off; width/height are physical pixels, already
 * floored to even (yuv420p requirement), and only used by x11grab.
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
