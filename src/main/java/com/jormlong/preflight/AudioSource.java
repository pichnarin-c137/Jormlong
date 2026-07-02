package com.jormlong.preflight;

/** One PulseAudio/PipeWire source as reported by `pactl list sources short`. */
public record AudioSource(String index, String name) {

    /** Monitor sources capture system output rather than a microphone. */
    public boolean isMonitor() {
        return name.endsWith(".monitor");
    }

    @Override
    public String toString() {
        return name;
    }
}
