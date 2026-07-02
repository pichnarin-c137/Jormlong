package com.jormlong.capture;

/** Requested video quality, translated to backend-specific flags in RecordingSession. */
public enum Quality {
    MEDIUM("Medium (smaller files)"),
    HIGH("High"),
    ULTRA("Ultra");

    private final String displayName;

    Quality(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
