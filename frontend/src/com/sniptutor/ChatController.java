package com.sniptutor;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import java.net.http.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ChatController {
    @FXML private TextField userInput;
    @FXML private VBox chatBox;
    @FXML private HBox imagePreviewContainer;
    @FXML private ImageView previewImageView;
    @FXML private Button btnStudent;
    @FXML private Button btnDeveloper;

    private final HttpClient client = HttpClient.newHttpClient();
    private static final String BACKEND_URL = "http://127.0.0.1:8000";

    private Path attachedImagePath = null;
    private String currentMode = "Student"; // "Student" or "Developer"

    @FXML
    private void sendMessage() {
        String text = userInput.getText();
        boolean hasText = (text != null && !text.trim().isEmpty());
        boolean hasImage = (attachedImagePath != null);

        if (!hasText && !hasImage) return;

        String query = hasText ? text.trim() : "";
        userInput.clear();

        // 1. If an image is attached, send to /process-image/
        if (hasImage) {
            Path imageToSend = attachedImagePath;
            removeAttachedImage(); // clear preview

            addMessage("You: " + (query.isEmpty() ? "📷 [Uploaded Screenshot]" : "📷 [Screenshot] " + query), "#8ab4f8");
            sendImageWithPromptToBackend(imageToSend, query);
        } 
        // 2. Otherwise send normal text to /process-text/
        else {
            addMessage("You: " + query, "#8ab4f8");
            sendTextToBackend(query);
        }
    }

    private void sendTextToBackend(String query) {
        String promptWithMode = "[" + currentMode + " Mode] " + query;
        String jsonPayload = "{\"text\":" + escapeJson(promptWithMode) + "}";

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
                addMessage("Gemini: " + reply, "#e3e3e3");
            })
            .exceptionally(e -> {
                addMessage("Connection Error: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()) 
                    + "\n(Make sure FastAPI backend is running on http://127.0.0.1:8000)", "#f28b82");
                return null;
            });
    }

    private void sendImageWithPromptToBackend(Path filePath, String optionalPrompt) {
        try {
            String boundary = "----SnipTutorBoundary" + System.currentTimeMillis();
            String CRLF = "\r\n";

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

            // 1. Add "prompt" form field (the user's question)
            if (optionalPrompt != null && !optionalPrompt.trim().isEmpty()) {
                out.write(("--" + boundary + CRLF).getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"prompt\"" + CRLF + CRLF).getBytes(StandardCharsets.UTF_8));
                out.write((optionalPrompt.trim() + CRLF).getBytes(StandardCharsets.UTF_8));
            }

            // 2. Add "mode" form field (Student or Developer)
            out.write(("--" + boundary + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"mode\"" + CRLF + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write((currentMode + CRLF).getBytes(StandardCharsets.UTF_8));

            // 3. Add "file" form field (the screenshot image)
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
                    String extracted = extractJsonField(body, "extracted_text");
                    String gemini = extractJsonField(body, "gemini_response");
                    if (gemini != null) {
                        if (extracted != null && !extracted.trim().isEmpty()) {
                            addMessage("📝 Extracted Text:\n" + extracted.trim(), "#c58af9");
                        }
                        addMessage("Gemini: " + gemini, "#e3e3e3");
                    } else {
                        String error = extractJsonField(body, "error");
                        addMessage("Error: " + (error != null ? error : body), "#f28b82");
                    }
                })
                .exceptionally(e -> {
                    addMessage("Connection Error: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), "#f28b82");
                    return null;
                });

        } catch (Exception e) {
            addMessage("Error uploading screenshot: " + e.getMessage(), "#f28b82");
        }
    }

    private javafx.stage.Stage stage;

    public void setStage(javafx.stage.Stage stage) {
        this.stage = stage;
    }

    @FXML
    public void activateSnippingTool() {
        SnippingOverlay overlay = new SnippingOverlay(capturedFile -> {
            attachedImagePath = capturedFile.toPath();
            previewImageView.setImage(new Image(capturedFile.toURI().toString()));
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

    @FXML
    private void removeAttachedImage() {
        attachedImagePath = null;
        previewImageView.setImage(null);
        imagePreviewContainer.setVisible(false);
        imagePreviewContainer.setManaged(false);
    }

    private void addMessage(String msg, String colorHex) {
        Platform.runLater(() -> {
            Label label = new Label(msg);
            label.setWrapText(true);
            label.setStyle("-fx-text-fill: " + colorHex + "; -fx-font-size: 13px; -fx-padding: 4px 8px; -fx-background-color: #2b2c2f; -fx-background-radius: 8px;");
            chatBox.getChildren().add(label);
        });
    }

    @FXML
    private void toggleStudentMode() {
        currentMode = "Student";
        btnStudent.setStyle("-fx-background-color: #1a73e8; -fx-text-fill: #ffffff; -fx-background-radius: 15; -fx-cursor: hand;");
        btnDeveloper.setStyle("-fx-background-color: #37393b; -fx-text-fill: #cccccc; -fx-background-radius: 15; -fx-cursor: hand;");
        addMessage("🎓 Switched to Student Mode: Explanations will be simple and easy to understand.", "#81c995");
    }

    @FXML
    private void toggleDeveloperMode() {
        currentMode = "Developer";
        btnDeveloper.setStyle("-fx-background-color: #1a73e8; -fx-text-fill: #ffffff; -fx-background-radius: 15; -fx-cursor: hand;");
        btnStudent.setStyle("-fx-background-color: #37393b; -fx-text-fill: #cccccc; -fx-background-radius: 15; -fx-cursor: hand;");
        addMessage("💻 Switched to Developer Mode: Explanations will be technical with code snippets.", "#81c995");
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
