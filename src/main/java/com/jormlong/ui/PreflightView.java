package com.jormlong.ui;

import com.jormlong.preflight.CheckResult;
import com.jormlong.preflight.Preflight;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/** First screen: the five environment checks, Re-check, and a gated Continue. */
public class PreflightView extends BorderPane {

    private final VBox rows = new VBox(12);
    private final Button recheckButton = new Button("Re-check");
    private final Button continueButton = new Button("Continue");
    private Preflight.Report report;

    public PreflightView(Consumer<Preflight.Report> onContinue) {
        getStyleClass().add("screen");

        Label title = new Label("Jormlong");
        title.getStyleClass().add("title");
        Label subtitle = new Label("Checking that this machine can record the screen…");
        subtitle.getStyleClass().add("subtitle");
        VBox header = new VBox(4, title, subtitle);

        continueButton.setDefaultButton(true);
        continueButton.setOnAction(e -> onContinue.accept(report));
        recheckButton.setOnAction(e -> runChecks());
        HBox buttons = new HBox(10, recheckButton, continueButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        setTop(header);
        setCenter(rows);
        setBottom(buttons);
        setPadding(new Insets(24));
        BorderPane.setMargin(rows, new Insets(20, 0, 20, 0));

        runChecks();
    }

    private void runChecks() {
        continueButton.setDisable(true);
        recheckButton.setDisable(true);
        rows.getChildren().setAll(new Label("Checking…"));
        Thread worker = new Thread(() -> {
            Preflight.Report result = Preflight.run();
            Platform.runLater(() -> show(result));
        }, "jormlong-preflight");
        worker.setDaemon(true);
        worker.start();
    }

    private void show(Preflight.Report result) {
        this.report = result;
        rows.getChildren().clear();
        for (CheckResult check : result.checks()) {
            rows.getChildren().add(row(check));
        }
        continueButton.setDisable(!result.ready());
        recheckButton.setDisable(false);
    }

    private HBox row(CheckResult check) {
        Label dot = new Label("●");
        dot.getStyleClass().add(check.ok() ? "dot-ok" : "dot-fail");

        Label name = new Label(check.label());
        name.getStyleClass().add("check-label");

        Label message = new Label(check.message());
        message.setWrapText(true);
        message.getStyleClass().add("check-message");

        VBox text = new VBox(2, name, message);
        if (!check.ok() && check.fix() != null) {
            TextField fix = new TextField(check.fix());
            fix.setEditable(false);
            fix.getStyleClass().add("fix-field");
            text.getChildren().add(fix);
        }
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(10, dot, text);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }
}
