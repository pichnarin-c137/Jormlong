package com.jormlong.preflight;

import com.jormlong.capture.CaptureStrategy;

import java.util.List;
import java.util.Optional;

/** Everything preflight learned about the machine, used to pick a capture strategy. */
public record Environment(
        SessionType sessionType,
        String desktopRaw,
        DesktopFamily desktopFamily,
        String ffmpegVersion,
        boolean hasGpuScreenRecorder,
        boolean hasWlScreenrec,
        boolean hasWfRecorder,
        List<AudioSource> audioSources) {

    public enum SessionType { X11, WAYLAND, UNKNOWN }

    /**
     * PORTAL: desktops that expose capture only through xdg-desktop-portal
     * (GNOME, KDE). Wlroots-based compositors expose wlr-screencopy directly.
     * Unknown Wayland desktops are treated as PORTAL by strategy().
     */
    public enum DesktopFamily { PORTAL, WLROOTS, OTHER }

    public Optional<CaptureStrategy> strategy() {
        if (sessionType == SessionType.X11) {
            return Optional.of(CaptureStrategy.X11GRAB);
        }
        if (sessionType == SessionType.WAYLAND) {
            if (desktopFamily == DesktopFamily.WLROOTS) {
                if (hasWfRecorder) {
                    return Optional.of(CaptureStrategy.WF_RECORDER);
                }
                if (hasWlScreenrec) {
                    return Optional.of(CaptureStrategy.WL_SCREENREC);
                }
            } else {
                if (hasGpuScreenRecorder) {
                    return Optional.of(CaptureStrategy.GPU_SCREEN_RECORDER);
                }
                if (hasWlScreenrec) {
                    return Optional.of(CaptureStrategy.WL_SCREENREC);
                }
            }
        }
        return Optional.empty();
    }
}
