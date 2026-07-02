package com.jormlong;

/**
 * Non-Application entry point for the shaded jar. Launching a JavaFX
 * Application subclass directly from a fat jar on the classpath aborts with
 * "JavaFX runtime components are missing"; going through a plain class avoids
 * that check.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        App.main(args);
    }
}
