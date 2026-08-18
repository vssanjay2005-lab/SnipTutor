package com.sniptutor;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.awt.Robot;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

public class SnippingOverlay {

    private double startX, startY;
    private double currentX, currentY;
    private Stage overlayStage;
    private final Consumer<File> onCaptureCallback;

    public SnippingOverlay(Consumer<File> onCaptureCallback) {
        this.onCaptureCallback = onCaptureCallback;
    }

    public void show() {
        Platform.runLater(() -> {
            Rectangle2D primaryScreen = Screen.getPrimary().getBounds();
            double screenWidth = primaryScreen.getWidth();
            double screenHeight = primaryScreen.getHeight();

            Group root = new Group();
            Canvas canvas = new Canvas(screenWidth, screenHeight);
            GraphicsContext gc = canvas.getGraphicsContext2D();

            drawDarkOverlay(gc, screenWidth, screenHeight, 0, 0, 0, 0);

            canvas.setOnMousePressed(e -> {
                startX = e.getScreenX();
                startY = e.getScreenY();
            });

            canvas.setOnMouseDragged(e -> {
                currentX = e.getScreenX();
                currentY = e.getScreenY();

                double x = Math.min(startX, currentX);
                double y = Math.min(startY, currentY);
                double width = Math.abs(currentX - startX);
                double height = Math.abs(currentY - startY);

                drawDarkOverlay(gc, screenWidth, screenHeight, x, y, width, height);
            });

            canvas.setOnMouseReleased(e -> {
                double endX = e.getScreenX();
                double endY = e.getScreenY();

                overlayStage.close();

                int captureX = (int) Math.min(startX, endX);
                int captureY = (int) Math.min(startY, endY);
                int captureWidth = (int) Math.abs(endX - startX);
                int captureHeight = (int) Math.abs(endY - startY);

                if (captureWidth > 5 && captureHeight > 5) {
                    captureSelectedArea(captureX, captureY, captureWidth, captureHeight);
                }
            });

            Scene scene = new Scene(root, screenWidth, screenHeight);
            scene.setFill(Color.TRANSPARENT);
            scene.setCursor(Cursor.CROSSHAIR);
            root.getChildren().add(canvas);

            overlayStage = new Stage();
            overlayStage.initStyle(StageStyle.TRANSPARENT);
            overlayStage.setAlwaysOnTop(true);
            overlayStage.setScene(scene);
            overlayStage.setX(primaryScreen.getMinX());
            overlayStage.setY(primaryScreen.getMinY());
            overlayStage.setWidth(screenWidth);
            overlayStage.setHeight(screenHeight);
            overlayStage.show();
        });
    }

    private void drawDarkOverlay(GraphicsContext gc, double screenW, double screenH, double selX, double selY, double selW, double selH) {
        gc.clearRect(0, 0, screenW, screenH);

        // Draw semi-transparent dark screen
        gc.setFill(Color.rgb(0, 0, 0, 0.45));
        gc.fillRect(0, 0, screenW, screenH);

        if (selW > 0 && selH > 0) {
            // Clear the selected rectangle so it looks bright/clear
            gc.clearRect(selX, selY, selW, selH);

            // Draw a stylish blue border around selection
            gc.setStroke(Color.rgb(66, 133, 244));
            gc.setLineWidth(2);
            gc.strokeRect(selX, selY, selW, selH);
        }
    }

    private void captureSelectedArea(int x, int y, int width, int height) {
        try {
            Robot robot = new Robot();
            Rectangle area = new Rectangle(x, y, width, height);
            BufferedImage screenCapture = robot.createScreenCapture(area);

            File savedFile = new File("screenshot.png");
            ImageIO.write(screenCapture, "png", savedFile);

            if (onCaptureCallback != null) {
                Platform.runLater(() -> onCaptureCallback.accept(savedFile));
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}