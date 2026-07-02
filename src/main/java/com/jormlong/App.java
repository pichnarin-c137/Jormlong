package com.jormlong;

import com.jormlong.ui.PreflightView;
import com.jormlong.ui.RecorderView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class App extends Application {

    private RecorderView recorderView;

    @Override
    public void start(Stage stage) {
        stage.setTitle("Jormlong");

        PreflightView preflight = new PreflightView(report -> {
            recorderView = new RecorderView(report.environment(), stage);
            stage.getScene().setRoot(recorderView);
        });

        Scene scene = new Scene(preflight, 580, 540);
        scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        stage.setScene(scene);

        stage.setOnCloseRequest(event -> {
            if (recorderView != null && recorderView.isRecording()) {
                event.consume();
                // finalize first; Stage.close() does not re-fire onCloseRequest
                recorderView.stopThen(() -> Platform.runLater(stage::close));
            }
        });

        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
