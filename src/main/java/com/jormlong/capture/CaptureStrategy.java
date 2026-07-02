package com.jormlong.capture;

/** Which external tool records the screen. Audio is always a separate FFmpeg process. */
public enum CaptureStrategy {
    GPU_SCREEN_RECORDER("gpu-screen-recorder"),
    WL_SCREENREC("wl-screenrec"),
    WF_RECORDER("wf-recorder"),
    X11GRAB("FFmpeg x11grab");

    private final String displayName;

    CaptureStrategy(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isWayland() {
        return this != X11GRAB;
    }

    /** FFmpeg-based capture stops via 'q' on stdin; the others take SIGINT. */
    public boolean stopsWithQ() {
        return this == X11GRAB;
    }
}
