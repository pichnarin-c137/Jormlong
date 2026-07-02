# Jormlong

A lightweight screen recorder for Linux. Jormlong does not encode anything
itself — it wraps the capture tool that fits your desktop (FFmpeg on X11, a
Wayland recorder otherwise), records microphone and optionally system audio
through FFmpeg, and muxes the result into a single MP4.

On launch, a preflight screen checks that your environment can actually
record (session type, FFmpeg, capture backend, audio sources) and shows a
copyable install command for anything that is missing. Once everything is
green, the recorder screen offers microphone selection, optional system-audio
mixing, FPS, and the output folder.

## Requirements

Build-time:

- JDK 17+ (with `jpackage` for building the .deb)
- Maven

Run-time (checked by the preflight screen):

| Environment | Packages |
|---|---|
| Everywhere | `ffmpeg`, `pulseaudio-utils` (for `pactl`; PipeWire's PulseAudio shim works too) |
| X11 | nothing extra — FFmpeg's `x11grab` captures the screen |
| Wayland, GNOME/KDE | `gpu-screen-recorder` (recommended) or `wl-screenrec` |
| Wayland, Sway/Hyprland/other wlroots | `wf-recorder` or `wl-screenrec` |

## Run in development

```sh
mvn javafx:run
```

## Build the .deb

```sh
./package.sh
sudo apt install ./dist/jormlong_*.deb
```

This shades a fat jar (entry point `com.jormlong.Launcher`, a plain class that
forwards to the JavaFX `Application` — launching an `Application` subclass
directly from a classpath fat jar aborts with "JavaFX runtime components are
missing") and feeds it to `jpackage --type deb`, which bundles its own Java
runtime. The result installs a menu entry under AudioVideo.

## Design notes

**Two processes, muxed afterward.** Video goes to a hidden temp file via the
capture backend; audio goes to a temp `.m4a` via `ffmpeg -f pulse` (with an
`amix` filter when "also record system audio" is checked). On stop, both temps
are muxed with `ffmpeg -c copy` — no re-encode — into
`Recording_<timestamp>.mp4`, and the temps are deleted. This keeps the flow
identical across all four backends and keeps the mic+system mixing logic in
one place instead of per-backend audio flags.

**A/V start alignment.** The audio process starts only once the video temp
file becomes non-empty. This matters on Wayland portal backends: video only
starts after you accept the OS screen-share dialog, so starting audio
immediately would make it lead by however long you took to click.

**The Wayland share dialog is normal.** On GNOME/KDE, the compositor owns
screen capture; when you press record, the OS shows its own dialog asking
which screen to share. Jormlong cannot skip it.

**Graceful stop.** FFmpeg processes are stopped by writing `q` to their stdin;
recorder backends get SIGINT. Both finalize their file properly on those
signals. Only if a process ignores them does Jormlong escalate to
SIGTERM and finally SIGKILL.

**gpu-screen-recorder vs wl-screenrec.** Despite being the commonly suggested
Wayland recorder, `wl-screenrec` needs the wlr-screencopy protocol, which
stock GNOME does not expose — on GNOME/KDE, `gpu-screen-recorder` (which uses
xdg-desktop-portal/PipeWire) is what actually works, so Jormlong prefers it
when both are installed. If capture fails anyway, the tool's own error output
is shown in the error dialog.

**FPS caveat.** `wl-screenrec` has no framerate flag; the FPS setting applies
where the backend supports it (x11grab, gpu-screen-recorder, wf-recorder).
