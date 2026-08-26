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

            // 1. Auto-Copy directly to Windows OS System Clipboard (ready for Ctrl+V in WhatsApp, Discord, Slack, etc.)
            try {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new TransferableImage(screenCapture), null);
            } catch (Exception clipEx) {
                clipEx.printStackTrace();
            }

            // 2. Auto-Archive to Pictures/SnipTutor folder with timestamp
            String userHome = System.getProperty("user.home");
            File snipDir = new File(userHome, "Pictures/SnipTutor");
            if (!snipDir.exists()) {
                snipDir.mkdirs();
            }

            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            File archivedFile = new File(snipDir, "Snip_" + timestamp + ".png");
            ImageIO.write(screenCapture, "png", archivedFile);

            // 3. Also update active screenshot.png for local temp reference
            File activeFile = new File("screenshot.png");
            ImageIO.write(screenCapture, "png", activeFile);

            if (onCaptureCallback != null) {
                Platform.runLater(() -> onCaptureCallback.accept(archivedFile));
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}

// Helper class to transfer images directly to Windows OS Clipboard
class TransferableImage implements java.awt.datatransfer.Transferable {
    private final java.awt.Image image;

    public TransferableImage(java.awt.Image image) {
        this.image = image;
    }

    @Override
    public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
        return new java.awt.datatransfer.DataFlavor[]{java.awt.datatransfer.DataFlavor.imageFlavor};
    }

    @Override
    public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) {
        return java.awt.datatransfer.DataFlavor.imageFlavor.equals(flavor);
    }

    @Override
    public Object getTransferData(java.awt.datatransfer.DataFlavor flavor) throws java.awt.datatransfer.UnsupportedFlavorException {
        if (!isDataFlavorSupported(flavor)) {
            throw new java.awt.datatransfer.UnsupportedFlavorException(flavor);
        }
        return image;
    }
}