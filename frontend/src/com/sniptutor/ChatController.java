package com.sniptutor;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ChatController {
    @FXML private TextField userInput;
    @FXML private VBox chatBox;
    @FXML private ScrollPane chatScrollPane;
    @FXML private VBox conversationsListBox;
    @FXML private Label currentChatTitleLabel;
    @FXML private HBox imagePreviewContainer;
    @FXML private ImageView previewImageView;
    @FXML private Label previewImageNameLabel;
    @FXML private Button btnTopCopy;
    @FXML private Button btnTopSave;
    @FXML private Button btnStudent;
    @FXML private Button btnDeveloper;

    private final HttpClient client = HttpClient.newHttpClient();
    private static final String BACKEND_URL = "http://127.0.0.1:8000";

    private final List<Conversation> conversations = new ArrayList<>();
    private Conversation currentConversation;
    private Path attachedImagePath = null;
    private String currentMode = "Student";
    private Stage stage;

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    @FXML
    public void initialize() {
        // Create initial default chat
        createNewChat();
    }

    @FXML
    public void createNewChat() {
        Conversation newConv = new Conversation("Chat " + (conversations.size() + 1));
        conversations.add(0, newConv);
        selectConversation(newConv);
    }

    private void selectConversation(Conversation conv) {
        this.currentConversation = conv;
        currentChatTitleLabel.setText(conv.getTitle());
        renderConversationsList();
        renderCurrentChatMessages();
    }

    private void renderConversationsList() {
        conversationsListBox.getChildren().clear();
        for (Conversation conv : conversations) {
            HBox item = new HBox(8);
            item.setAlignment(Pos.CENTER_LEFT);
            boolean isSelected = (conv == currentConversation);
            item.setStyle("-fx-background-color: " + (isSelected ? "#2b2c2f" : "transparent") + "; " +
                          "-fx-background-radius: 10px; -fx-padding: 8px 10px; -fx-cursor: hand;");

            // 1. Chat bubble vector icon
            javafx.scene.shape.SVGPath chatIcon = new javafx.scene.shape.SVGPath();
            chatIcon.setContent("M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z");
            chatIcon.setScaleX(0.55);
            chatIcon.setScaleY(0.55);
            chatIcon.setFill(javafx.scene.paint.Color.web(isSelected ? "#8ab4f8" : "#8e918f"));

            // 2. Title Label
            Label titleLabel = new Label(conv.getTitle());
            titleLabel.setStyle("-fx-text-fill: " + (isSelected ? "#ffffff" : "#c4c7c5") + "; -fx-font-size: 12px;");
            HBox.setHgrow(titleLabel, Priority.ALWAYS);
            titleLabel.setMaxWidth(110);

            // 3. Rename button with SVG Pencil icon
            javafx.scene.shape.SVGPath editSvg = new javafx.scene.shape.SVGPath();
            editSvg.setContent("M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z");
            editSvg.setScaleX(0.5);
            editSvg.setScaleY(0.5);
            editSvg.setFill(javafx.scene.paint.Color.web("#9aa0a6"));

            Button renameBtn = new Button("", editSvg);
            renameBtn.setTooltip(new Tooltip("Rename conversation"));
            renameBtn.setStyle("-fx-background-color: transparent; -fx-cursor: hand; -fx-padding: 2;");
            renameBtn.setOnAction(e -> renameConversation(conv));

            // 4. Delete button with SVG Trash icon
            javafx.scene.shape.SVGPath trashSvg = new javafx.scene.shape.SVGPath();
            trashSvg.setContent("M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z");
            trashSvg.setScaleX(0.5);
            trashSvg.setScaleY(0.5);
            trashSvg.setFill(javafx.scene.paint.Color.web("#9aa0a6"));

            Button deleteBtn = new Button("", trashSvg);
            deleteBtn.setTooltip(new Tooltip("Delete conversation"));
            deleteBtn.setStyle("-fx-background-color: transparent; -fx-cursor: hand; -fx-padding: 2;");
            deleteBtn.setOnAction(e -> deleteConversation(conv));

            item.setOnMouseClicked(e -> {
                if (e.getTarget() != renameBtn && e.getTarget() != deleteBtn) {
                    selectConversation(conv);
                }
            });

            item.getChildren().addAll(chatIcon, titleLabel, renameBtn, deleteBtn);
            conversationsListBox.getChildren().add(item);
        }
    }

    private void renameConversation(Conversation conv) {
        TextInputDialog dialog = new TextInputDialog(conv.getTitle());
        dialog.setTitle("Rename Conversation");
        dialog.setHeaderText(null);
        dialog.setContentText("Enter new title:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(newTitle -> {
            if (!newTitle.trim().isEmpty()) {
                conv.setTitle(newTitle.trim());
                if (conv == currentConversation) {
                    currentChatTitleLabel.setText(conv.getTitle());
                }
                renderConversationsList();
            }
        });
    }

    private void deleteConversation(Conversation conv) {
        if (conversations.size() <= 1) {
            // Keep at least one chat
            conv.getMessages().clear();
            conv.setTitle("New Chat");
            selectConversation(conv);
            return;
        }
        conversations.remove(conv);
        if (currentConversation == conv) {
            selectConversation(conversations.get(0));
        } else {
            renderConversationsList();
        }
    }

    private void renderCurrentChatMessages() {
        chatBox.getChildren().clear();
        if (currentConversation != null) {
            for (ChatMessage msg : currentConversation.getMessages()) {
                displayMessageBubble(msg.getSender(), msg.getText(), msg.getImagePath());
            }
        }
    }

    @FXML
    private void sendMessage() {
        String text = userInput.getText();
        boolean hasText = (text != null && !text.trim().isEmpty());
        boolean hasImage = (attachedImagePath != null);

        if (!hasText && !hasImage) return;

        String query = hasText ? text.trim() : "";
        userInput.clear();

        // Auto-title conversation on first message
        if (currentConversation.getMessages().isEmpty() && hasText) {
            String autoTitle = query.length() > 20 ? query.substring(0, 20) + "..." : query;
            currentConversation.setTitle(autoTitle);
            currentChatTitleLabel.setText(autoTitle);
            renderConversationsList();
        }

        String historyContext = buildHistoryContext();

        if (hasImage) {
            Path imageToSend = attachedImagePath;
            String imagePathStr = imageToSend.toUri().toString();
            removeAttachedImage();

            currentConversation.addMessage("You", query.isEmpty() ? "[Uploaded Screenshot]" : query, imagePathStr);
            displayMessageBubble("You", query.isEmpty() ? "[Uploaded Screenshot]" : query, imagePathStr);

            sendImageToBackend(imageToSend, query, historyContext);
        } else {
            currentConversation.addMessage("You", query, null);
            displayMessageBubble("You", query, null);

            sendTextToBackend(query, historyContext);
        }
    }

    private String buildHistoryContext() {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : currentConversation.getMessages()) {
            sb.append(msg.getSender()).append(": ").append(msg.getText()).append("\n");
        }
        return sb.toString();
    }

    private void sendTextToBackend(String query, String history) {
        String jsonPayload = "{\"text\":" + escapeJson(query) + 
                             ",\"history\":" + escapeJson(history) + 
                             ",\"mode\":" + escapeJson(currentMode) + "}";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BACKEND_URL + "/process-text/"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
            .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .thenAccept(response -> {
                String body = response.body();
                String reply = extractJsonField(body, "gemini_response");
                if (reply == null) {
                    String error = extractJsonField(body, "error");
                    reply = (error != null) ? "Error: " + error : body;
                }
                final String finalReply = reply;
                Platform.runLater(() -> {
                    currentConversation.addMessage("SnipTutor", finalReply, null);
                    displayMessageBubble("SnipTutor", finalReply, null);
                });
            })
            .exceptionally(e -> {
                Platform.runLater(() -> {
                    String err = "Connection Error: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                    displayMessageBubble("SnipTutor", err, null);
                });
                return null;
            });
    }

    private void sendImageToBackend(Path filePath, String optionalPrompt, String history) {
        try {
            String boundary = "----SnipTutorBoundary" + System.currentTimeMillis();
            String CRLF = "\r\n";

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

            // Prompt
            if (optionalPrompt != null && !optionalPrompt.trim().isEmpty()) {
                out.write(("--" + boundary + CRLF).getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"prompt\"" + CRLF + CRLF).getBytes(StandardCharsets.UTF_8));
                out.write((optionalPrompt.trim() + CRLF).getBytes(StandardCharsets.UTF_8));
            }

            // Mode
            out.write(("--" + boundary + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"mode\"" + CRLF + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write((currentMode + CRLF).getBytes(StandardCharsets.UTF_8));

            // History
            if (history != null && !history.trim().isEmpty()) {
                out.write(("--" + boundary + CRLF).getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"history\"" + CRLF + CRLF).getBytes(StandardCharsets.UTF_8));
                out.write((history.trim() + CRLF).getBytes(StandardCharsets.UTF_8));
            }

            // File
            out.write(("--" + boundary + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filePath.getFileName() + "\"" + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: image/png" + CRLF + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write(Files.readAllBytes(filePath));
            out.write((CRLF + "--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8));

            byte[] requestBody = out.toByteArray();

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BACKEND_URL + "/process-image/"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenAccept(response -> {
                    String body = response.body();
                    String gemini = extractJsonField(body, "gemini_response");
                    String reply = (gemini != null) ? gemini : extractJsonField(body, "error");
                    if (reply == null) reply = body;

                    final String finalReply = reply;
                    Platform.runLater(() -> {
                        currentConversation.addMessage("SnipTutor", finalReply, null);
                        displayMessageBubble("SnipTutor", finalReply, null);
                    });
                })
                .exceptionally(e -> {
                    Platform.runLater(() -> {
                        displayMessageBubble("SnipTutor", "Connection Error: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), null);
                    });
                    return null;
                });

        } catch (Exception e) {
            displayMessageBubble("SnipTutor", "Error uploading screenshot: " + e.getMessage(), null);
        }
    }

    private void displayMessageBubble(String sender, String text, String imagePath) {
        boolean isUser = sender.equals("You");

        VBox bubbleContainer = new VBox(6);
        bubbleContainer.setAlignment(isUser ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        // Header (Sender Name)
        Label senderLabel = new Label(isUser ? "You" : "✨ SnipTutor");
        senderLabel.setStyle("-fx-text-fill: " + (isUser ? "#8ab4f8" : "#c58af9") + "; -fx-font-size: 12px; -fx-font-weight: bold;");

        // Message Box
        VBox messageBox = new VBox(8);
        messageBox.setMaxWidth(550);
        messageBox.setStyle("-fx-background-color: " + (isUser ? "#2b2c2f" : "#1e1f20") + "; " +
                            "-fx-background-radius: 16px; -fx-padding: 12px 16px; " +
                            "-fx-border-color: " + (isUser ? "#3c4043" : "#2b2c2f") + "; -fx-border-radius: 16px;");

        if (imagePath != null && !imagePath.isEmpty()) {
            try {
                ImageView iv = new ImageView(new Image(imagePath));
                iv.setFitWidth(200);
                iv.setPreserveRatio(true);
                iv.setStyle("-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.4), 6, 0, 0, 2);");
                messageBox.getChildren().add(iv);
            } catch (Exception ignored) {}
        }

        Label contentLabel = new Label(text);
        contentLabel.setWrapText(true);
        contentLabel.setStyle("-fx-text-fill: #e3e3e3; -fx-font-size: 13px; -fx-line-spacing: 3px;");
        messageBox.getChildren().add(contentLabel);

        bubbleContainer.getChildren().addAll(senderLabel, messageBox);
        chatBox.getChildren().add(bubbleContainer);

        // Scroll to bottom
        chatScrollPane.setVvalue(1.0);
    }

    @FXML
    public void activateSnippingTool() {
        SnippingOverlay overlay = new SnippingOverlay(capturedFile -> {
            attachedImagePath = capturedFile.toPath();
            previewImageView.setImage(new Image(capturedFile.toURI().toString()));
            if (previewImageNameLabel != null) {
                previewImageNameLabel.setText(capturedFile.getName());
            }
            imagePreviewContainer.setVisible(true);
            imagePreviewContainer.setManaged(true);
            if (stage != null) {
                stage.show();
                stage.setIconified(false);
                stage.toFront();
                stage.requestFocus();
            }
            userInput.requestFocus();
        });
        overlay.show();
    }

    private Path getActiveOrLatestSnippetPath() {
        if (attachedImagePath != null && Files.exists(attachedImagePath)) {
            return attachedImagePath;
        }
        java.io.File localScreenshot = new java.io.File("screenshot.png");
        if (localScreenshot.exists()) {
            return localScreenshot.toPath();
        }
        String userHome = System.getProperty("user.home");
        java.io.File snipDir = new java.io.File(userHome, "Pictures/SnipTutor");
        if (snipDir.exists() && snipDir.isDirectory()) {
            java.io.File[] files = snipDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".png"));
            if (files != null && files.length > 0) {
                java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                return files[0].toPath();
            }
        }
        return null;
    }

    @FXML
    private void copyAttachedImageToClipboard() {
        Path targetPath = getActiveOrLatestSnippetPath();
        if (targetPath == null) return;
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(targetPath.toFile());
            if (img != null) {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new TransferableImage(img), null);
                
                if (btnTopCopy != null) {
                    btnTopCopy.setText("✅ Copied!");
                    new Thread(() -> {
                        try { Thread.sleep(1500); } catch (Exception ignored) {}
                        Platform.runLater(() -> btnTopCopy.setText("📋 Copy"));
                    }).start();
                }
                if (previewImageNameLabel != null && attachedImagePath != null) {
                    previewImageNameLabel.setText("✅ Copied to Clipboard!");
                    new Thread(() -> {
                        try { Thread.sleep(1500); } catch (Exception ignored) {}
                        Platform.runLater(() -> {
                            if (attachedImagePath != null && previewImageNameLabel != null) {
                                previewImageNameLabel.setText(attachedImagePath.getFileName().toString());
                            }
                        });
                    }).start();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void saveAttachedImageAs() {
        Path targetPath = getActiveOrLatestSnippetPath();
        if (targetPath == null) return;
        try {
            javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
            fileChooser.setTitle("Save Snippet");
            fileChooser.setInitialFileName(targetPath.getFileName().toString());
            fileChooser.getExtensionFilters().addAll(
                new javafx.stage.FileChooser.ExtensionFilter("PNG Image (*.png)", "*.png"),
                new javafx.stage.FileChooser.ExtensionFilter("JPEG Image (*.jpg)", "*.jpg"),
                new javafx.stage.FileChooser.ExtensionFilter("All Files", "*.*")
            );
            java.io.File dest = fileChooser.showSaveDialog(stage);
            if (dest != null) {
                Files.copy(targetPath, dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if (btnTopSave != null) {
                    btnTopSave.setText("💾 Saved!");
                    new Thread(() -> {
                        try { Thread.sleep(1500); } catch (Exception ignored) {}
                        Platform.runLater(() -> btnTopSave.setText("💾 Save As"));
                    }).start();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void openSnipsFolder() {
        try {
            String userHome = System.getProperty("user.home");
            java.io.File snipDir = new java.io.File(userHome, "Pictures/SnipTutor");
            if (!snipDir.exists()) snipDir.mkdirs();
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(snipDir);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void openAttachedImageInViewer() {
        Path targetPath = getActiveOrLatestSnippetPath();
        if (targetPath == null) return;
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(targetPath.toFile());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void removeAttachedImage() {
        attachedImagePath = null;
        previewImageView.setImage(null);
        if (previewImageNameLabel != null) {
            previewImageNameLabel.setText("");
        }
        imagePreviewContainer.setVisible(false);
        imagePreviewContainer.setManaged(false);
    }

    @FXML
    private void toggleStudentMode() {
        currentMode = "Student";
        btnStudent.setStyle("-fx-background-color: #1a73e8; -fx-text-fill: #ffffff; -fx-background-radius: 15; -fx-cursor: hand;");
        btnDeveloper.setStyle("-fx-background-color: #2b2c2f; -fx-text-fill: #cccccc; -fx-background-radius: 15; -fx-cursor: hand;");
    }

    @FXML
    private void toggleDeveloperMode() {
        currentMode = "Developer";
        btnDeveloper.setStyle("-fx-background-color: #1a73e8; -fx-text-fill: #ffffff; -fx-background-radius: 15; -fx-cursor: hand;");
        btnStudent.setStyle("-fx-background-color: #2b2c2f; -fx-text-fill: #cccccc; -fx-background-radius: 15; -fx-cursor: hand;");
    }

    private String escapeJson(String raw) {
        if (raw == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private String extractJsonField(String json, String key) {
        if (json == null) return null;
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start == -1) return null;
        start += pattern.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (start >= json.length()) return null;

        if (json.charAt(start) == '"') {
            start++;
            StringBuilder val = new StringBuilder();
            boolean escaped = false;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (escaped) {
                    if (c == 'n') val.append('\n');
                    else if (c == 'r') val.append('\r');
                    else if (c == 't') val.append('\t');
                    else if (c == '"') val.append('"');
                    else if (c == '\\') val.append('\\');
                    else val.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    return val.toString();
                } else {
                    val.append(c);
                }
            }
        }
        return null;
    }
}
