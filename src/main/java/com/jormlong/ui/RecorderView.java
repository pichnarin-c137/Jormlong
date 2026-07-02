package com.jormlong.ui;

import com.jormlong.capture.CaptureStrategy;
import com.jormlong.capture.Quality;
import com.jormlong.capture.RecordingConfig;
import com.jormlong.capture.RecordingSession;
import com.jormlong.preflight.AudioSource;
import com.jormlong.preflight.Environment;
import com.jormlong.util.Cmd;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Second screen: audio/fps/folder settings, the record toggle, and the timer. */
public class RecorderView extends VBox {

    private final Environment env;
    private final Stage stage;

    private final CheckBox micCheck = new CheckBox("Record microphone");
    private final ComboBox<AudioSource> micBox = new ComboBox<>();
    private final CheckBox systemAudioBox = new CheckBox("Record system audio (what the computer plays)");
    private final Spinner<Integer> fpsSpinner = new Spinner<>(10, 120, 30);
    private final ComboBox<Quality> qualityBox = new ComboBox<>();
    private final TextField folderField = new TextField();
    private final Button browseButton = new Button("Browse…");
    private final Button recordButton = new Button("● Start Recording");
    private final Label elapsedLabel = new Label("00:00:00");
    private final Label statusLabel = new Label(" ");

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jormlong-timer");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jormlong-worker");
        t.setDaemon(true);
        return t;
    });

    private RecordingSession session;
    private ScheduledFuture<?> tick;
    private long startedNanos;
    private boolean recording;

    public RecorderView(Environment env, Stage stage) {
        super(14);
        this.env = env;
        this.stage = stage;
        getStyleClass().add("screen");
        setPadding(new Insets(24));

        Label title = new Label("Jormlong");
        title.getStyleClass().add("title");
        Label subtitle = new Label("Recording via " + env.strategy().map(CaptureStrategy::displayName).orElse("?"));
        subtitle.getStyleClass().add("subtitle");

        List<AudioSource> mics = env.audioSources().stream().filter(s -> !s.isMonitor()).toList();
        micBox.setItems(FXCollections.observableArrayList(mics));
        if (!mics.isEmpty()) {
            micBox.getSelectionModel().select(0);
        }
        micBox.setMaxWidth(Double.MAX_VALUE);
        micCheck.setSelected(!mics.isEmpty());
        micCheck.setDisable(mics.isEmpty());
        micBox.setDisable(mics.isEmpty());
        micCheck.setOnAction(e -> micBox.setDisable(!micCheck.isSelected()));
        micCheck.setTooltip(new Tooltip(
                "Adds your voice from the microphone. Turn off to keep room noise out of the recording."));

        boolean hasMonitor = env.audioSources().stream().anyMatch(AudioSource::isMonitor);
        systemAudioBox.setSelected(hasMonitor);
        if (!hasMonitor) {
            systemAudioBox.setDisable(true);
            systemAudioBox.setTooltip(new Tooltip(
                    "No system-output monitor source was found, so system audio cannot be captured."));
        } else {
            systemAudioBox.setTooltip(new Tooltip(
                    "Captures the sound the computer plays (e.g. a video's own audio) digitally — no room noise."));
        }

        fpsSpinner.setEditable(true);
        Tooltip.install(fpsSpinner, new Tooltip(
                "Frames per second. Applies where the capture backend supports it (wl-screenrec has no fps flag)."));

        qualityBox.setItems(FXCollections.observableArrayList(Quality.values()));
        qualityBox.getSelectionModel().select(Quality.HIGH);
        qualityBox.setMaxWidth(Double.MAX_VALUE);
        Tooltip.install(qualityBox, new Tooltip(
                "Higher quality means sharper text and cleaner motion, but larger files."));

        folderField.setEditable(false);
        folderField.setText(defaultVideosDir().toString());
        HBox.setHgrow(folderField, Priority.ALWAYS);
        browseButton.setOnAction(e -> browse());
        HBox folderRow = new HBox(8, folderField, browseButton);

        recordButton.getStyleClass().add("record-button");
        recordButton.setMaxWidth(Double.MAX_VALUE);
        recordButton.setOnAction(e -> {
            if (recording) {
                stopRecording(null);
            } else {
                startRecording();
            }
        });

        elapsedLabel.getStyleClass().add("elapsed");
        statusLabel.getStyleClass().add("status");
        statusLabel.setWrapText(true);
        HBox timerRow = new HBox(elapsedLabel);
        timerRow.setAlignment(Pos.CENTER);

        HBox micRow = new HBox(10, micCheck, micBox);
        micRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(micBox, Priority.ALWAYS);

        getChildren().addAll(title, subtitle,
                labeled("Audio", new VBox(8, systemAudioBox, micRow)),
                labeled("Frames per second", fpsSpinner),
                labeled("Quality", qualityBox),
                labeled("Save recordings to", folderRow));

        if (env.strategy().map(CaptureStrategy::isWayland).orElse(false)) {
            Label portalNote = new Label(
                    "On Wayland the OS shows its own screen-share dialog when recording starts — this is normal.");
            portalNote.setWrapText(true);
            portalNote.getStyleClass().add("portal-note");
            portalNote.setTooltip(new Tooltip(
                    "The compositor owns screen capture on Wayland; pick the screen to record in its dialog."));
            getChildren().add(portalNote);
        }

        getChildren().addAll(recordButton, timerRow, statusLabel);
    }

    public boolean isRecording() {
        return recording;
    }

    /** Used by the window-close handler: finalize the recording, then run {@code after}. */
    public void stopThen(Runnable after) {
        if (recording) {
            stopRecording(after);
        } else {
            after.run();
        }
    }

    private void startRecording() {
        CaptureStrategy strategy = env.strategy().orElse(null);
        if (strategy == null) {
            showError("Cannot record", "No capture strategy is available.");
            return;
        }

        Screen screen = Screen.getPrimary();
        int width = evenFloor(screen.getBounds().getWidth() * screen.getOutputScaleX());
        int height = evenFloor(screen.getBounds().getHeight() * screen.getOutputScaleY());
        String display = System.getenv("DISPLAY");
        String mic = micCheck.isSelected() && micBox.getValue() != null
                ? micBox.getValue().name() : null;
        String monitor = systemAudioBox.isSelected()
                ? env.audioSources().stream().filter(AudioSource::isMonitor)
                        .findFirst().map(AudioSource::name).orElse(null)
                : null;
        // one source records alone; two get amix'd; none → silent video
        String audioPrimary = mic != null ? mic : monitor;
        String audioSecondary = mic != null ? monitor : null;

        RecordingConfig cfg = new RecordingConfig(strategy,
                display == null ? ":0" : display,
                width, height, readFps(), qualityBox.getValue(), audioPrimary, audioSecondary,
                Path.of(folderField.getText()));

        session = new RecordingSession(cfg, message ->
                Platform.runLater(() -> onAsyncFailure(message)));
        try {
            session.start();
        } catch (IOException e) {
            session = null;
            showError("Could not start recording", e.getMessage());
            return;
        }

        recording = true;
        setSettingsDisabled(true);
        recordButton.setText("■ Stop Recording");
        recordButton.getStyleClass().add("recording");
        statusLabel.setText("Recording…");
        startedNanos = System.nanoTime();
        elapsedLabel.setText("00:00:00");
        tick = timer.scheduleAtFixedRate(
                () -> Platform.runLater(this::updateElapsed), 500, 500, TimeUnit.MILLISECONDS);
    }

    private void stopRecording(Runnable after) {
        recordButton.setDisable(true);
        statusLabel.setText("Finalizing…");
        stopTimer();
        RecordingSession current = session;
        worker.execute(() -> {
            Path saved = null;
            Exception failure = null;
            try {
                saved = current.stopAndFinalize();
            } catch (Exception e) {
                failure = e;
            }
            Path savedFinal = saved;
            Exception failureFinal = failure;
            Platform.runLater(() -> {
                resetUi();
                if (failureFinal != null) {
                    showError("Recording failed while finalizing", failureFinal.getMessage());
                } else if (savedFinal != null) {
                    statusLabel.setText("Saved: " + savedFinal);
                }
                // savedFinal == null with no exception → the async failure
                // callback already reported the error
                if (after != null) {
                    after.run();
                }
            });
        });
    }

    private void onAsyncFailure(String message) {
        if (!recording) {
            return;
        }
        stopTimer();
        resetUi();
        showError("Recording stopped unexpectedly", message);
    }

    private void resetUi() {
        recording = false;
        session = null;
        setSettingsDisabled(false);
        recordButton.setDisable(false);
        recordButton.setText("● Start Recording");
        recordButton.getStyleClass().remove("recording");
        if (statusLabel.getText().startsWith("Finalizing") || statusLabel.getText().startsWith("Recording")) {
            statusLabel.setText(" ");
        }
    }

    private void setSettingsDisabled(boolean disabled) {
        boolean noMic = micBox.getItems().isEmpty();
        micCheck.setDisable(disabled || noMic);
        micBox.setDisable(disabled || noMic || !micCheck.isSelected());
        systemAudioBox.setDisable(disabled
                || env.audioSources().stream().noneMatch(AudioSource::isMonitor));
        fpsSpinner.setDisable(disabled);
        qualityBox.setDisable(disabled);
        browseButton.setDisable(disabled);
    }

    private void updateElapsed() {
        if (!recording) {
            return;
        }
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedNanos);
        elapsedLabel.setText(String.format("%02d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60));
    }

    private void stopTimer() {
        if (tick != null) {
            tick.cancel(false);
            tick = null;
        }
    }

    private int readFps() {
        // the editable spinner editor may hold unparsed text
        try {
            int typed = Integer.parseInt(fpsSpinner.getEditor().getText().trim());
            return Math.max(10, Math.min(120, typed));
        } catch (NumberFormatException e) {
            return fpsSpinner.getValue();
        }
    }

    private void browse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose output folder");
        File initial = new File(folderField.getText());
        if (initial.isDirectory()) {
            chooser.setInitialDirectory(initial);
        }
        File chosen = chooser.showDialog(stage);
        if (chosen != null) {
            folderField.setText(chosen.getAbsolutePath());
        }
    }

    private static Path defaultVideosDir() {
        Cmd.Result xdg = Cmd.run(5, "xdg-user-dir", "VIDEOS");
        String dir = xdg.ok() ? xdg.stdout().trim() : "";
        if (!dir.isEmpty() && !dir.equals(System.getProperty("user.home"))) {
            return Path.of(dir);
        }
        return Path.of(System.getProperty("user.home"), "Videos");
    }

    private static int evenFloor(double value) {
        int v = (int) Math.floor(value);
        return v - (v % 2);
    }

    private static VBox labeled(String text, javafx.scene.Node control) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        return new VBox(4, label, control);
    }

    private void showError(String header, String detail) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Jormlong");
        alert.setHeaderText(header);
        alert.setContentText("The recording tool reported an error. Details below.");
        TextArea area = new TextArea(detail == null ? "(no details)" : detail);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(12);
        alert.getDialogPane().setExpandableContent(area);
        alert.getDialogPane().setExpanded(true);
        alert.initOwner(stage);
        alert.show();
    }
}
