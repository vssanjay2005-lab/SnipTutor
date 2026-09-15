package com.sniptutor;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URL;

public class SnipTutorApp extends Application {
    private GlobalHotkeyManager hotkeyManager;
    private Stage mainStage;
    private ChatController controller;
    private TrayIcon trayIcon;

    @Override
    public void start(Stage stage) throws Exception {
        this.mainStage = stage;

        // 1. Tell JavaFX NOT to terminate when the window is closed
        Platform.setImplicitExit(false);

        // 2. Load the chat FXML UI
        URL fxmlUrl = getClass().getResource("/com/sniptutor/chat.fxml");
        if (fxmlUrl == null) {
            fxmlUrl = getClass().getResource("chat.fxml");
        }
        if (fxmlUrl == null) {
            throw new IllegalStateException("Could not find chat.fxml in classpath.");
        }

        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Scene scene = new Scene(loader.load(), 950, 650);
        this.controller = loader.getController();
        this.controller.setStage(stage);

        stage.setTitle("SnipTutor - Universal AI Side-Workflow Companion");
        stage.setScene(scene);

        // 3. When user clicks [X], hide the window instead of quitting
        stage.setOnCloseRequest(e -> {
            e.consume(); // Prevent default close action
            stage.hide();
        });

        // 4. Setup Windows System Tray Icon
        setupSystemTray();

        // 5. Setup Global Hotkey (Ctrl + Shift + Space)
        hotkeyManager = new GlobalHotkeyManager(() -> {
            // Trigger Snipping Overlay directly even if window is closed/hidden
            controller.activateSnippingTool();
        });
        hotkeyManager.register();

        // Show window on initial launch
        stage.show();
    }

    private void setupSystemTray() {
        if (!SystemTray.isSupported()) return;

        try {
            SystemTray tray = SystemTray.getSystemTray();

            // Create a simple tray icon programmatically (blue square with 'S')
            BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = image.createGraphics();
            g2d.setColor(new java.awt.Color(26, 115, 232));
            g2d.fillRoundRect(0, 0, 16, 16, 4, 4);
            g2d.setColor(java.awt.Color.WHITE);
            g2d.setFont(new Font("Segoe UI", Font.BOLD, 11));
            g2d.drawString("S", 4, 12);
            g2d.dispose();

            PopupMenu popup = new PopupMenu();

            MenuItem snipItem = new MenuItem("📸 Snip (Ctrl+Shift+Space)");
            snipItem.addActionListener(e -> Platform.runLater(() -> controller.activateSnippingTool()));

            MenuItem openItem = new MenuItem("💬 Open SnipTutor");
            openItem.addActionListener(e -> Platform.runLater(() -> {
                mainStage.show();
                mainStage.setIconified(false);
                mainStage.toFront();
            }));

            MenuItem exitItem = new MenuItem("❌ Exit SnipTutor");
            exitItem.addActionListener(e -> {
                try {
                    java.net.http.HttpClient.newHttpClient().send(
                        java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create("http://127.0.0.1:8000/restore-os-workarea/"))
                            .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                            .build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding()
                    );
                } catch (Exception ignored) {}
                if (hotkeyManager != null) {
                    hotkeyManager.unregister();
                }
                tray.remove(trayIcon);
                Platform.exit();
                System.exit(0);
            });

            popup.add(snipItem);
            popup.add(openItem);
            popup.addSeparator();
            popup.add(exitItem);

            trayIcon = new TrayIcon(image, "SnipTutor AI Side-Workflow Companion", popup);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> Platform.runLater(() -> {
                mainStage.show();
                mainStage.setIconified(false);
                mainStage.toFront();
            }));

            tray.add(trayIcon);

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
