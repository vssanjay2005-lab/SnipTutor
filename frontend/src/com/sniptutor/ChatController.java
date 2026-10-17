package com.sniptutor;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ChatController {
    @FXML private TextField userInput;
    @FXML private VBox chatBox;
    @FXML private ScrollPane chatScrollPane;
    @FXML private HBox sidebarOverlayWrapper;
    @FXML private VBox sidebarContainer;
    @FXML private Button btnToggleSidebar;
    @FXML private VBox conversationsListBox;
    @FXML private Label currentChatTitleLabel;

    // Multi-Media & Universal Attachment Chip Bindings
    @FXML private HBox attachmentPreviewContainer;
    @FXML private Label attachmentIconLabel;
    @FXML private Label attachmentNameLabel;
    @FXML private Label attachmentDetailLabel;
    @FXML private ImageView previewImageView;
    @FXML private Button btnAttach;

    @FXML private HBox liveContextChip;
    @FXML private Label liveContextLabel;
    @FXML private Button btnTopCopy;
    @FXML private Button btnTopSave;
    @FXML private Button btnSideDock;
    @FXML private Button btnStudent;
    @FXML private Button btnDeveloper;

    // User Account Card FXML bindings
    @FXML private Label userAvatarLabel;
    @FXML private Label userNameLabel;
    @FXML private Label userStatusLabel;
    @FXML private Button btnAuthAction;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(45))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    public static String BACKEND_URL = resolveBackendUrl();

    private static String resolveBackendUrl() {
        String envUrl = System.getenv("SNIPTUTOR_BACKEND_URL");
        if (envUrl != null && !envUrl.trim().isEmpty()) {
            return envUrl.trim().replaceAll("/+$", "");
        }
        String[] localConfigCandidates = {"backend_url.txt", "app/backend_url.txt"};
        for (String candidate : localConfigCandidates) {
            try {
                File f = new File(candidate);
                if (f.exists()) {
                    for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                            return trimmed.replaceAll("/+$", "");
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        try {
            File file = new File(new File(System.getProperty("user.home"), ".sniptutor"), "session.properties");
            if (file.exists()) {
                Properties p = new Properties();
                try (FileInputStream fis = new FileInputStream(file)) {
                    p.load(fis);
                }
                String savedUrl = p.getProperty("backend_url");
                if (savedUrl != null && !savedUrl.trim().isEmpty()) {
                    return savedUrl.trim().replaceAll("/+$", "");
                }
            }
        } catch (Exception ignored) {}

        // If local dev backend (127.0.0.1:8000) is running, use it; otherwise use live Render cloud backend!
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 8000), 350);
            return "http://127.0.0.1:8000";
        } catch (Exception ignored) {
            return "https://sniptutor.onrender.com";
        }
    }

    private final List<Conversation> conversations = new ArrayList<>();
    private Conversation currentConversation;
    
    // Attachment State (Files, Images, Docs, Spreadsheets, URLs)
    private Path attachedFilePath = null;
    private String attachedWebUrl = null;
    private boolean isImageAttachment = false;

    private String currentMode = "Student";
    private Stage stage;

    // Authentication State
    private Integer loggedInUserId = null;
    private String loggedInUsername = null;

    private boolean isLiveContextActive = true;
    private boolean isSideDocked = false;
    private double standardX = 100, standardY = 100, standardWidth = 950, standardHeight = 650;

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    @FXML
    public void initialize() {
        if (chatScrollPane != null) {
            chatScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            chatScrollPane.setFitToWidth(true);
            chatScrollPane.setHmin(0);
            chatScrollPane.setHmax(0);
            chatScrollPane.setHvalue(0);
            chatScrollPane.hvalueProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal.doubleValue() != 0.0) {
                    chatScrollPane.setHvalue(0.0);
                }
            });
            if (chatBox != null) {
                chatBox.setFillWidth(true);
                chatBox.prefWidthProperty().bind(chatScrollPane.widthProperty().subtract(16));
                chatBox.maxWidthProperty().bind(chatScrollPane.widthProperty().subtract(16));
            }
        }
        // Load persistent user session or create default guest chat
        loadSession();
    }

    @FXML
    public void createNewChat() {
        Conversation newConv = new Conversation("Chat " + (conversations.size() + 1));
        conversations.add(0, newConv);
        selectConversation(newConv);
        if (sidebarOverlayWrapper != null && sidebarOverlayWrapper.isVisible()) {
            toggleSidebar();
        }
        if (loggedInUserId != null) {
            syncConversationToBackend(newConv);
        }
    }

    private void selectConversation(Conversation conv) {
        this.currentConversation = conv;
        currentChatTitleLabel.setText(conv.getTitle());
        renderConversationsList();
        renderCurrentChatMessages();
        if (sidebarOverlayWrapper != null && sidebarOverlayWrapper.isVisible()) {
            toggleSidebar();
        }
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
                if (loggedInUserId != null) {
                    syncConversationToBackend(conv);
                }
            }
        });
    }

    private void deleteConversation(Conversation conv) {
        String convId = conv.getId();
        if (conversations.size() <= 1) {
            // Keep at least one chat
            conv.getMessages().clear();
            conv.setTitle("New Chat");
            selectConversation(conv);
            if (loggedInUserId != null) {
                syncConversationToBackend(conv);
            }
            return;
        }
        conversations.remove(conv);
        if (loggedInUserId != null) {
            deleteConversationFromBackend(convId);
        }
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
        if (chatScrollPane != null) {
            chatScrollPane.setHvalue(0.0);
            chatScrollPane.setVvalue(1.0);
        }
    }

    @FXML
    public void toggleSidebar() {
        if (sidebarOverlayWrapper != null) {
            boolean isVisible = sidebarOverlayWrapper.isVisible();
            sidebarOverlayWrapper.setVisible(!isVisible);
            sidebarOverlayWrapper.setManaged(!isVisible);
            if (!isVisible) {
                sidebarOverlayWrapper.toFront();
            }
        }
    }

    @FXML
    public void toggleLiveContext() {
        isLiveContextActive = !isLiveContextActive;
        if (liveContextChip != null) {
            liveContextChip.setVisible(isLiveContextActive);
            liveContextChip.setManaged(isLiveContextActive);
        }
    }

    // ==============================================================================
    // UNIVERSAL ATTACHMENT SYSTEM (IMAGES, PDF, DOC, PPT, EXCEL, CODE, URL)
    // ==============================================================================

    @FXML
    public void handleAttachButton() {
        ContextMenu menu = new ContextMenu();
        menu.setStyle("-fx-background-color: #1e1f20; -fx-border-color: #2b2c2f; -fx-padding: 4px;");

        MenuItem itemFile = new MenuItem("📁 Upload File / Document (PDF, PPT, DOC, Excel, Code, Media)");
        itemFile.setStyle("-fx-text-fill: #e3e3e3; -fx-font-size: 12px;");
        itemFile.setOnAction(e -> openFileChooser());

        MenuItem itemUrl = new MenuItem("🌐 Attach Web URL / Article Link");
        itemUrl.setStyle("-fx-text-fill: #e3e3e3; -fx-font-size: 12px;");
        itemUrl.setOnAction(e -> openUrlInputDialog());

        MenuItem itemSnip = new MenuItem("📸 Snip Screen Region");
        itemSnip.setStyle("-fx-text-fill: #e3e3e3; -fx-font-size: 12px;");
        itemSnip.setOnAction(e -> activateSnippingTool());

        menu.getItems().addAll(itemFile, itemUrl, itemSnip);
        menu.show(btnAttach, javafx.geometry.Side.TOP, 0, -5);
    }

    private void openFileChooser() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Attach Any Document, Media, or Code File");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("All Supported Files (*.pdf, *.docx, *.pptx, *.xlsx, *.png, *.jpg, *.txt, *.py...)",
                "*.pdf", "*.docx", "*.doc", "*.pptx", "*.ppt", "*.xlsx", "*.xls", "*.csv",
                "*.png", "*.jpg", "*.jpeg", "*.webp", "*.bmp", "*.gif",
                "*.py", "*.java", "*.cpp", "*.c", "*.h", "*.cs", "*.js", "*.ts", "*.json", "*.html", "*.css", "*.sql", "*.txt", "*.md",
                "*.mp3", "*.wav", "*.mp4", "*.mkv"),
            new FileChooser.ExtensionFilter("Documents & Slides (*.pdf, *.docx, *.pptx)", "*.pdf", "*.docx", "*.pptx", "*.doc", "*.ppt"),
            new FileChooser.ExtensionFilter("Spreadsheets & Data (*.xlsx, *.csv)", "*.xlsx", "*.xls", "*.csv"),
            new FileChooser.ExtensionFilter("Images (*.png, *.jpg, *.webp)", "*.png", "*.jpg", "*.jpeg", "*.webp", "*.bmp", "*.gif"),
            new FileChooser.ExtensionFilter("Code & Scripts (*.py, *.java, *.cpp, *.json, *.txt)", "*.py", "*.java", "*.cpp", "*.c", "*.cs", "*.js", "*.ts", "*.json", "*.txt", "*.md", "*.sql"),
            new FileChooser.ExtensionFilter("All Files (*.*)", "*.*")
        );

        File selected = chooser.showOpenDialog(stage);
        if (selected != null) {
            attachFile(selected);
        }
    }

    private void openUrlInputDialog() {
        TextInputDialog dialog = new TextInputDialog("https://");
        dialog.setTitle("Attach Web URL");
        dialog.setHeaderText("Analyze Web Page or Online Documentation");
        dialog.setContentText("Enter Web URL:");

        DialogPane dp = dialog.getDialogPane();
        dp.setStyle("-fx-background-color: #1e1f20; -fx-text-fill: #e3e3e3;");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(url -> {
            if (!url.trim().isEmpty() && !url.trim().equals("https://")) {
                attachWebUrl(url.trim());
            }
        });
    }

    public void attachFile(File file) {
        attachedFilePath = file.toPath();
        attachedWebUrl = null;

        String name = file.getName();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase() : "";

        // Choose appropriate icon & description
        String icon = "📄";
        String typeDesc = "Document";
        isImageAttachment = false;

        if (ext.equals(".pdf")) {
            icon = "📕";
            typeDesc = "PDF Document";
        } else if (ext.equals(".docx") || ext.equals(".doc")) {
            icon = "📘";
            typeDesc = "Word Document";
        } else if (ext.equals(".pptx") || ext.equals(".ppt")) {
            icon = "📙";
            typeDesc = "PowerPoint Presentation";
        } else if (ext.equals(".xlsx") || ext.equals(".xls") || ext.equals(".csv")) {
            icon = "📊";
            typeDesc = "Spreadsheet / Dataset";
        } else if (Arrays.asList(".png", ".jpg", ".jpeg", ".webp", ".bmp", ".gif").contains(ext)) {
            icon = "🖼️";
            typeDesc = "Image Media";
            isImageAttachment = true;
            try {
                previewImageView.setImage(new Image(file.toURI().toString()));
                previewImageView.setVisible(true);
                previewImageView.setManaged(true);
            } catch (Exception ignored) {}
        } else if (Arrays.asList(".py", ".java", ".cpp", ".c", ".js", ".ts", ".json", ".sql", ".html", ".css", ".txt", ".md").contains(ext)) {
            icon = "💻";
            typeDesc = "Source Code / Text";
        }

        if (!isImageAttachment) {
            previewImageView.setVisible(false);
            previewImageView.setManaged(false);
        }

        long sizeKb = file.length() / 1024;
        attachmentIconLabel.setText(icon);
        attachmentNameLabel.setText(name);
        attachmentDetailLabel.setText(typeDesc + " (" + (sizeKb > 0 ? sizeKb + " KB" : file.length() + " B") + ") • Ready to analyze");

        attachmentPreviewContainer.setVisible(true);
        attachmentPreviewContainer.setManaged(true);
        userInput.requestFocus();
    }

    public void attachWebUrl(String url) {
        attachedWebUrl = url;
        attachedFilePath = null;
        isImageAttachment = false;

        previewImageView.setVisible(false);
        previewImageView.setManaged(false);

        attachmentIconLabel.setText("🌐");
        attachmentNameLabel.setText(url.length() > 40 ? url.substring(0, 40) + "..." : url);
        attachmentDetailLabel.setText("Web URL Extract • Ready to fetch & analyze");

        attachmentPreviewContainer.setVisible(true);
        attachmentPreviewContainer.setManaged(true);
        userInput.requestFocus();
    }

    @FXML
    public void removeAttachment() {
        attachedFilePath = null;
        attachedWebUrl = null;
        isImageAttachment = false;

        previewImageView.setImage(null);
        previewImageView.setVisible(false);
        previewImageView.setManaged(false);

        attachmentPreviewContainer.setVisible(false);
        attachmentPreviewContainer.setManaged(false);
    }

    // ==============================================================================
    // MESSAGE SENDING & BACKEND INGESTION
    // ==============================================================================

    @FXML
    private void sendMessage() {
        String text = userInput.getText();
        boolean hasText = (text != null && !text.trim().isEmpty());
        boolean hasFile = (attachedFilePath != null);
        boolean hasUrl = (attachedWebUrl != null);

        if (!hasText && !hasFile && !hasUrl && !isLiveContextActive) return;

        String query = hasText ? text.trim() : "";
        userInput.clear();

        // Auto-title conversation on first message
        if (currentConversation.getMessages().isEmpty()) {
            String autoTitle = !query.isEmpty() ? query : (hasFile ? attachedFilePath.getFileName().toString() : "Analysis");
            if (autoTitle.length() > 22) autoTitle = autoTitle.substring(0, 22) + "...";
            currentConversation.setTitle(autoTitle);
            currentChatTitleLabel.setText(autoTitle);
            renderConversationsList();
        }

        String historyContext = buildHistoryContext();

        // 1. Web URL attached
        if (hasUrl) {
            String url = attachedWebUrl;
            removeAttachment();

            String displayPrompt = "🌐 [Attached URL: " + url + "]\n" + (query.isEmpty() ? "Please analyze and explain this webpage." : query);
            currentConversation.addMessage("You", displayPrompt, null);
            displayMessageBubble("You", displayPrompt, null);
            if (loggedInUserId != null) syncConversationToBackend(currentConversation);

            sendUrlToBackend(url, query, historyContext);
        }
        // 2. File / Image attached
        else if (hasFile) {
            Path filePath = attachedFilePath;
            boolean isImg = isImageAttachment;
            String fileName = filePath.getFileName().toString();
            String imageUriStr = isImg ? filePath.toUri().toString() : null;
            removeAttachment();

            String displayPrompt = isImg 
                ? (query.isEmpty() ? "[Uploaded Screenshot]" : query)
                : ("📁 [Attached: " + fileName + "]\n" + (query.isEmpty() ? "Please analyze this file and break down its key contents." : query));

            currentConversation.addMessage("You", displayPrompt, imageUriStr);
            displayMessageBubble("You", displayPrompt, imageUriStr);
            if (loggedInUserId != null) syncConversationToBackend(currentConversation);

            if (isImg) {
                sendImageToBackend(filePath, query, historyContext);
            } else {
                sendGenericFileToBackend(filePath, query, historyContext);
            }
        }
        // 3. Zero-Click Live Screen Capture (skip heavy screen capture for simple greetings like 'hi'/'hey')
        else if (isLiveContextActive && !query.toLowerCase().matches("^(hi|hey|hello|hola|yo|thanks|thank you|ok|okay|bye)[!.?]*$")) {
            String activeQuery = query.isEmpty() ? "Explain what is on my screen and help me solve it." : query;
            captureAndSendLiveContext(activeQuery, historyContext);
        }
        // 4. Pure Text Query
        else {
            currentConversation.addMessage("You", query, null);
            displayMessageBubble("You", query, null);
            if (loggedInUserId != null) syncConversationToBackend(currentConversation);

            sendTextToBackend(query, historyContext);
        }
    }

    private void captureAndSendLiveContext(String query, String historyContext) {
        currentConversation.addMessage("You", query, null);
        displayMessageBubble("You", query, null);
        if (loggedInUserId != null) syncConversationToBackend(currentConversation);

        VBox loadingNode = displayLoadingBubble("SnipTutor");

        new Thread(() -> {
            try {
                java.awt.Robot robot = new java.awt.Robot();
                javafx.geometry.Rectangle2D screenBounds = javafx.stage.Screen.getPrimary().getBounds();
                java.awt.Rectangle area = new java.awt.Rectangle(
                    (int) screenBounds.getMinX(),
                    (int) screenBounds.getMinY(),
                    (int) screenBounds.getWidth(),
                    (int) screenBounds.getHeight()
                );
                java.awt.image.BufferedImage liveCapture = robot.createScreenCapture(area);

                String userHome = System.getProperty("user.home");
                java.io.File snipDir = new java.io.File(userHome, "Pictures/SnipTutor");
                if (!snipDir.exists()) snipDir.mkdirs();

                String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
                java.io.File liveFile = new java.io.File(snipDir, "LiveContext_" + timestamp + ".png");
                javax.imageio.ImageIO.write(liveCapture, "png", liveFile);

                sendImageToBackendWithLoading(liveFile.toPath(), query, historyContext, loadingNode);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    removeLoadingBubble(loadingNode);
                    sendTextToBackend(query, historyContext);
                });
            }
        }).start();
    }

    private String buildHistoryContext() {
        if (currentConversation == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : currentConversation.getMessages()) {
            sb.append(msg.getSender()).append(": ").append(msg.getText()).append("\n");
        }
        return sb.toString();
    }

    private void sendTextToBackend(String query, String history) {
        VBox loadingNode = displayLoadingBubble("SnipTutor");
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
                    removeLoadingBubble(loadingNode);
                    currentConversation.addMessage("SnipTutor", finalReply, null);
                    displayMessageBubble("SnipTutor", finalReply, null);
                    if (loggedInUserId != null) syncConversationToBackend(currentConversation);
                });
            })
            .exceptionally(e -> {
                Platform.runLater(() -> {
                    removeLoadingBubble(loadingNode);
                    String err = "Connection Error: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                    displayMessageBubble("SnipTutor", err, null);
                });
                return null;
            });
    }

    private void sendUrlToBackend(String url, String optionalPrompt, String history) {
        VBox loadingNode = displayLoadingBubble("SnipTutor");
        String jsonPayload = "{\"url\":" + escapeJson(url) + 
                             ",\"prompt\":" + escapeJson(optionalPrompt) + 
                             ",\"history\":" + escapeJson(history) + 
                             ",\"mode\":" + escapeJson(currentMode) + "}";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BACKEND_URL + "/process-url/"))
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
                    removeLoadingBubble(loadingNode);
                    currentConversation.addMessage("SnipTutor", finalReply, null);
                    displayMessageBubble("SnipTutor", finalReply, null);
                    if (loggedInUserId != null) syncConversationToBackend(currentConversation);
                });
            })
            .exceptionally(e -> {
                Platform.runLater(() -> {
                    removeLoadingBubble(loadingNode);
                    String err = "Connection Error: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                    displayMessageBubble("SnipTutor", err, null);
                });
                return null;
            });
    }

    private void sendGenericFileToBackend(Path filePath, String optionalPrompt, String history) {
        VBox loadingNode = displayLoadingBubble("SnipTutor");
        try {
            String boundary = "----SnipTutorBoundary" + System.currentTimeMillis();
            String CRLF = "\r\n";
            ByteArrayOutputStream out = new ByteArrayOutputStream();

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
            String filename = filePath.getFileName().toString();
            out.write(("--" + boundary + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"" + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: application/octet-stream" + CRLF + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write(Files.readAllBytes(filePath));
            out.write((CRLF + "--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8));

            byte[] requestBody = out.toByteArray();

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BACKEND_URL + "/process-file/"))
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
                        removeLoadingBubble(loadingNode);
                        currentConversation.addMessage("SnipTutor", finalReply, null);
                        displayMessageBubble("SnipTutor", finalReply, null);
                        if (loggedInUserId != null) syncConversationToBackend(currentConversation);
                    });
                })
                .exceptionally(e -> {
                    Platform.runLater(() -> {
                        removeLoadingBubble(loadingNode);
                        displayMessageBubble("SnipTutor", "Connection Error: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), null);
                    });
                    return null;
                });
        } catch (Exception e) {
            Platform.runLater(() -> {
                removeLoadingBubble(loadingNode);
                displayMessageBubble("SnipTutor", "Error uploading file: " + e.getMessage(), null);
            });
        }
    }

    private void sendImageToBackend(Path filePath, String optionalPrompt, String history) {
        VBox loadingNode = displayLoadingBubble("SnipTutor");
        sendImageToBackendWithLoading(filePath, optionalPrompt, history, loadingNode);
    }

    private void sendImageToBackendWithLoading(Path filePath, String optionalPrompt, String history, VBox loadingNode) {
        try {
            String boundary = "----SnipTutorBoundary" + System.currentTimeMillis();
            String CRLF = "\r\n";
            ByteArrayOutputStream out = new ByteArrayOutputStream();

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
                        removeLoadingBubble(loadingNode);
                        currentConversation.addMessage("SnipTutor", finalReply, null);
                        displayMessageBubble("SnipTutor", finalReply, null);
                        if (loggedInUserId != null) syncConversationToBackend(currentConversation);
                    });
                })
                .exceptionally(e -> {
                    Platform.runLater(() -> {
                        removeLoadingBubble(loadingNode);
                        displayMessageBubble("SnipTutor", "Connection Error: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), null);
                    });
                    return null;
                });

        } catch (Exception e) {
            Platform.runLater(() -> {
                removeLoadingBubble(loadingNode);
                displayMessageBubble("SnipTutor", "Error uploading screenshot: " + e.getMessage(), null);
            });
        }
    }

    private VBox displayLoadingBubble(String sender) {
        VBox bubbleContainer = new VBox(6);
        bubbleContainer.setAlignment(Pos.CENTER_LEFT);

        Label senderLabel = new Label("✨ SnipTutor");
        senderLabel.setStyle("-fx-text-fill: #c58af9; -fx-font-size: 12px; -fx-font-weight: bold;");

        HBox messageBox = new HBox(8);
        messageBox.setAlignment(Pos.CENTER_LEFT);
        messageBox.setMaxWidth(580);
        messageBox.setStyle("-fx-background-color: #1e1f20; -fx-background-radius: 16px; -fx-padding: 10px 16px; -fx-border-color: #2b2c2f; -fx-border-radius: 16px;");

        Label sparkLabel = new Label("✦");
        sparkLabel.setStyle("-fx-text-fill: #c58af9; -fx-font-size: 14px; -fx-font-weight: bold;");

        Label contentLabel = new Label("Reading prompt & analyzing context...");
        contentLabel.setStyle("-fx-text-fill: #9aa0a6; -fx-font-size: 13px; -fx-font-style: italic;");
        messageBox.getChildren().addAll(sparkLabel, contentLabel);

        bubbleContainer.getChildren().addAll(senderLabel, messageBox);
        chatBox.getChildren().add(bubbleContainer);
        chatScrollPane.setVvalue(1.0);

        // Dynamic multi-stage thinking animation
        String[] thinkingStages = {
            "Reading prompt & defining problem context...",
            "Analyzing problem intent & edge cases...",
            "Synthesizing solution & formulating steps...",
            "Formulating conversational response..."
        };

        final int[] stageIdx = {0};
        javafx.animation.Timeline timeline = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.millis(1100), e -> {
                stageIdx[0] = (stageIdx[0] + 1) % thinkingStages.length;
                javafx.animation.FadeTransition ft = new javafx.animation.FadeTransition(javafx.util.Duration.millis(250), contentLabel);
                ft.setFromValue(1.0);
                ft.setToValue(0.3);
                ft.setOnFinished(evt -> {
                    contentLabel.setText(thinkingStages[stageIdx[0]]);
                    javafx.animation.FadeTransition ftIn = new javafx.animation.FadeTransition(javafx.util.Duration.millis(250), contentLabel);
                    ftIn.setFromValue(0.3);
                    ftIn.setToValue(1.0);
                    ftIn.play();
                });
                ft.play();
            })
        );
        timeline.setCycleCount(javafx.animation.Animation.INDEFINITE);
        timeline.play();

        bubbleContainer.getProperties().put("timeline", timeline);
        return bubbleContainer;
    }

    private void removeLoadingBubble(VBox loadingNode) {
        if (loadingNode != null) {
            Object tl = loadingNode.getProperties().get("timeline");
            if (tl instanceof javafx.animation.Timeline) {
                ((javafx.animation.Timeline) tl).stop();
            }
            chatBox.getChildren().remove(loadingNode);
        }
    }

    private void displayMessageBubble(String sender, String text, String imagePath) {
        boolean isUser = sender.equals("You");

        VBox bubbleContainer = new VBox(6);
        bubbleContainer.setAlignment(Pos.CENTER_LEFT);
        bubbleContainer.setFillWidth(true);
        bubbleContainer.prefWidthProperty().bind(chatBox.widthProperty());
        bubbleContainer.maxWidthProperty().bind(chatBox.widthProperty());

        // Header (Sender Name)
        Label senderLabel = new Label(isUser ? "👤 You" : "✨ SnipTutor");
        senderLabel.setStyle("-fx-text-fill: " + (isUser ? "#8ab4f8" : "#c58af9") + "; -fx-font-size: 12px; -fx-font-weight: bold;");

        // Message Box
        VBox messageBox = new VBox(8);
        messageBox.setFillWidth(true);
        messageBox.prefWidthProperty().bind(chatBox.widthProperty());
        messageBox.maxWidthProperty().bind(chatBox.widthProperty());
        messageBox.setStyle("-fx-background-color: " + (isUser ? "#2b2c2f" : "#1e1f20") + "; " +
                            "-fx-background-radius: 16px; -fx-padding: 12px 16px; " +
                            "-fx-border-color: " + (isUser ? "#3c4043" : "#2b2c2f") + "; -fx-border-radius: 16px;");

        if (imagePath != null && !imagePath.isEmpty()) {
            try {
                ImageView iv = new ImageView(new Image(imagePath));
                iv.setFitWidth(220);
                iv.setPreserveRatio(true);
                iv.setStyle("-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.4), 6, 0, 0, 2);");
                messageBox.getChildren().add(iv);
            } catch (Exception ignored) {}
        }

        // Render with Rich Markdown Block Renderer and 1-Click Interactive Follow-up Suggestion Chips
        VBox renderedContent = MarkdownRenderer.render(text, isUser, followUpPrompt -> {
            userInput.setText(followUpPrompt);
            sendMessage();
        });
        renderedContent.setFillWidth(true);
        renderedContent.prefWidthProperty().bind(messageBox.widthProperty().subtract(32));
        renderedContent.maxWidthProperty().bind(messageBox.widthProperty().subtract(32));

        messageBox.getChildren().add(renderedContent);

        bubbleContainer.getChildren().addAll(senderLabel, messageBox);
        chatBox.getChildren().add(bubbleContainer);

        // Scroll to bottom and lock hvalue strictly to 0 after layout pass
        chatScrollPane.setHvalue(0.0);
        chatScrollPane.setVvalue(1.0);
        Platform.runLater(() -> {
            chatBox.applyCss();
            chatBox.layout();
            chatScrollPane.layout();
            chatScrollPane.setHvalue(0.0);
            chatScrollPane.setVvalue(1.0);
        });
    }

    // ==============================================================================
    // USER AUTHENTICATION & CONVERSATION SYNC IMPLEMENTATION
    // ==============================================================================

    @FXML
    public void handleAuthAction() {
        if (loggedInUserId != null) {
            // Confirm Logout Dialog
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("SnipTutor - Logout");
            alert.setHeaderText("Sign out from @" + loggedInUsername + "?");
            alert.setContentText("Your conversation history is securely saved in the database. Are you sure you want to sign out?");

            DialogPane dialogPane = alert.getDialogPane();
            dialogPane.setStyle("-fx-background-color: #1e1f20; -fx-font-family: 'Segoe UI', sans-serif;");
            
            Optional<ButtonType> result = alert.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                logout();
            }
        } else {
            showAuthDialog();
        }
    }

    private void showAuthDialog() {
        Stage authStage = new Stage();
        authStage.initModality(Modality.APPLICATION_MODAL);
        if (stage != null) authStage.initOwner(stage);
        authStage.setTitle("SnipTutor - Account Sign In");

        VBox root = new VBox(14);
        root.setAlignment(Pos.CENTER);
        root.setStyle("-fx-background-color: #1e1f20; -fx-padding: 24px; -fx-font-family: 'Segoe UI', sans-serif;");
        root.setPrefWidth(340);

        Label titleLabel = new Label("✨ SnipTutor Account");
        titleLabel.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 16px; -fx-font-weight: bold;");

        Label subLabel = new Label("Sign in to save and access your conversation history across sessions.");
        subLabel.setWrapText(true);
        subLabel.setStyle("-fx-text-fill: #9aa0a6; -fx-font-size: 11px; -fx-text-alignment: center;");

        final boolean[] isRegisterMode = {false};
        HBox toggleBox = new HBox(6);
        toggleBox.setAlignment(Pos.CENTER);
        toggleBox.setStyle("-fx-background-color: #131314; -fx-background-radius: 10px; -fx-padding: 3px;");

        Button tabLogin = new Button("Sign In");
        Button tabRegister = new Button("Create Account");

        tabLogin.setStyle("-fx-background-color: #2b2c2f; -fx-text-fill: #ffffff; -fx-background-radius: 8px; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 5 14; -fx-cursor: hand;");
        tabRegister.setStyle("-fx-background-color: transparent; -fx-text-fill: #9aa0a6; -fx-background-radius: 8px; -fx-font-size: 11px; -fx-padding: 5 14; -fx-cursor: hand;");

        HBox.setHgrow(tabLogin, Priority.ALWAYS);
        HBox.setHgrow(tabRegister, Priority.ALWAYS);
        tabLogin.setMaxWidth(Double.MAX_VALUE);
        tabRegister.setMaxWidth(Double.MAX_VALUE);
        toggleBox.getChildren().addAll(tabLogin, tabRegister);

        VBox formBox = new VBox(10);
        TextField usernameField = new TextField();
        usernameField.setPromptText("Username");
        usernameField.setStyle("-fx-background-color: #2b2c2f; -fx-text-fill: #ffffff; -fx-prompt-text-fill: #8e918f; -fx-background-radius: 8px; -fx-padding: 8 12; -fx-font-size: 12px;");

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");
        passwordField.setStyle("-fx-background-color: #2b2c2f; -fx-text-fill: #ffffff; -fx-prompt-text-fill: #8e918f; -fx-background-radius: 8px; -fx-padding: 8 12; -fx-font-size: 12px;");

        Label statusMsgLabel = new Label("");
        statusMsgLabel.setWrapText(true);
        statusMsgLabel.setStyle("-fx-text-fill: #f28b82; -fx-font-size: 11px;");

        Button btnSubmit = new Button("Sign In");
        btnSubmit.setMaxWidth(Double.MAX_VALUE);
        btnSubmit.setStyle("-fx-background-color: #1a73e8; -fx-text-fill: #ffffff; -fx-background-radius: 8px; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 8 0; -fx-cursor: hand;");

        tabLogin.setOnAction(e -> {
            isRegisterMode[0] = false;
            tabLogin.setStyle("-fx-background-color: #2b2c2f; -fx-text-fill: #ffffff; -fx-background-radius: 8px; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 5 14; -fx-cursor: hand;");
            tabRegister.setStyle("-fx-background-color: transparent; -fx-text-fill: #9aa0a6; -fx-background-radius: 8px; -fx-font-size: 11px; -fx-padding: 5 14; -fx-cursor: hand;");
            btnSubmit.setText("Sign In");
            statusMsgLabel.setText("");
        });

        tabRegister.setOnAction(e -> {
            isRegisterMode[0] = true;
            tabRegister.setStyle("-fx-background-color: #2b2c2f; -fx-text-fill: #ffffff; -fx-background-radius: 8px; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 5 14; -fx-cursor: hand;");
            tabLogin.setStyle("-fx-background-color: transparent; -fx-text-fill: #9aa0a6; -fx-background-radius: 8px; -fx-font-size: 11px; -fx-padding: 5 14; -fx-cursor: hand;");
            btnSubmit.setText("Create Account");
            statusMsgLabel.setText("");
        });

        Runnable submitAction = () -> {
            String u = usernameField.getText().trim();
            String p = passwordField.getText();
            if (u.isEmpty() || p.isEmpty()) {
                statusMsgLabel.setText("Please enter both username and password.");
                return;
            }

            btnSubmit.setDisable(true);
            btnSubmit.setText("Connecting...");
            statusMsgLabel.setText("");

            String endpoint = isRegisterMode[0] ? "/auth/register/" : "/auth/login/";
            String jsonPayload = "{\"username\":" + escapeJson(u) + ",\"password\":" + escapeJson(p) + "}";

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BACKEND_URL + endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                .build();

            client.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenAccept(res -> {
                    String body = res.body();
                    Platform.runLater(() -> {
                        btnSubmit.setDisable(false);
                        btnSubmit.setText(isRegisterMode[0] ? "Create Account" : "Sign In");
                        try {
                            Object parsed = JsonUtil.parse(body);
                            if (parsed instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> map = (Map<String, Object>) parsed;
                                String status = (String) map.get("status");
                                if ("ok".equalsIgnoreCase(status)) {
                                    Number uidNum = (Number) map.get("user_id");
                                    int uid = uidNum != null ? uidNum.intValue() : 1;
                                    String uname = (String) map.get("username");
                                    if (uname == null) uname = u;

                                    setAuthenticatedUser(uid, uname);
                                    saveSession(uid, uname);
                                    authStage.close();
                                    loadConversationsFromBackend(uid);
                                } else {
                                    String msg = (String) map.get("message");
                                    statusMsgLabel.setText(msg != null ? msg : "Authentication failed.");
                                }
                            } else {
                                statusMsgLabel.setText("Unexpected server response.");
                            }
                        } catch (Exception ex) {
                            statusMsgLabel.setText("Error parsing response: " + ex.getMessage());
                        }
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        btnSubmit.setDisable(false);
                        btnSubmit.setText(isRegisterMode[0] ? "Create Account" : "Sign In");
                        statusMsgLabel.setText("Connection error: " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
                    });
                    return null;
                });
        };

        btnSubmit.setOnAction(e -> submitAction.run());
        usernameField.setOnAction(e -> passwordField.requestFocus());
        passwordField.setOnAction(e -> submitAction.run());

        formBox.getChildren().addAll(usernameField, passwordField, statusMsgLabel, btnSubmit);
        root.getChildren().addAll(titleLabel, subLabel, toggleBox, formBox);

        Scene scene = new Scene(root);
        authStage.setScene(scene);
        authStage.show();
    }

    private void setAuthenticatedUser(int userId, String username) {
        this.loggedInUserId = userId;
        this.loggedInUsername = username;

        if (userNameLabel != null) userNameLabel.setText(username);
        if (userStatusLabel != null) userStatusLabel.setText("Online");
        if (btnAuthAction != null) {
            btnAuthAction.setText("Logout");
            btnAuthAction.setStyle("-fx-background-color: #3c4043; -fx-text-fill: #e3e3e3; -fx-background-radius: 10; -fx-font-size: 10px; -fx-padding: 3 8; -fx-cursor: hand;");
        }
    }

    private void logout() {
        this.loggedInUserId = null;
        this.loggedInUsername = null;
        clearSession();

        if (userNameLabel != null) userNameLabel.setText("Guest");
        if (userStatusLabel != null) userStatusLabel.setText("Not Logged In");
        if (btnAuthAction != null) {
            btnAuthAction.setText("Login");
            btnAuthAction.setStyle("-fx-background-color: #1a73e8; -fx-text-fill: white; -fx-background-radius: 10; -fx-font-size: 10px; -fx-font-weight: bold; -fx-padding: 3 8; -fx-cursor: hand;");
        }

        conversations.clear();
        createNewChat();
    }

    private void saveSession(int userId, String username) {
        try {
            File dir = new File(System.getProperty("user.home"), ".sniptutor");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "session.properties");
            Properties p = new Properties();
            p.setProperty("user_id", String.valueOf(userId));
            p.setProperty("username", username);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                p.store(fos, "SnipTutor User Session");
            }
        } catch (Exception ignored) {}
    }

    private void loadSession() {
        try {
            File file = new File(new File(System.getProperty("user.home"), ".sniptutor"), "session.properties");
            if (file.exists()) {
                Properties p = new Properties();
                try (FileInputStream fis = new FileInputStream(file)) {
                    p.load(fis);
                }
                String uidStr = p.getProperty("user_id");
                String uname = p.getProperty("username");
                if (uidStr != null && uname != null && !uidStr.trim().isEmpty() && !uname.trim().isEmpty()) {
                    int uid = Integer.parseInt(uidStr.trim());
                    setAuthenticatedUser(uid, uname.trim());
                    loadConversationsFromBackend(uid);
                    return;
                }
            }
        } catch (Exception ignored) {}

        // Fallback to fresh chat if no session
        createNewChat();
    }

    private void clearSession() {
        try {
            File file = new File(new File(System.getProperty("user.home"), ".sniptutor"), "session.properties");
            if (file.exists()) {
                file.delete();
            }
        } catch (Exception ignored) {}
    }

    private void loadConversationsFromBackend(int userId) {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BACKEND_URL + "/conversations/?user_id=" + userId))
            .GET()
            .build();

        client.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .thenAccept(res -> {
                String body = res.body();
                Platform.runLater(() -> {
                    try {
                        Object parsed = JsonUtil.parse(body);
                        if (parsed instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> map = (Map<String, Object>) parsed;
                            String status = (String) map.get("status");
                            if ("ok".equalsIgnoreCase(status)) {
                                @SuppressWarnings("unchecked")
                                List<Object> list = (List<Object>) map.get("conversations");
                                conversations.clear();
                                if (list != null && !list.isEmpty()) {
                                    for (Object obj : list) {
                                        if (obj instanceof Map) {
                                            @SuppressWarnings("unchecked")
                                            Map<String, Object> cMap = (Map<String, Object>) obj;
                                            String cid = (String) cMap.get("id");
                                            String title = (String) cMap.get("title");
                                            Conversation conv = new Conversation(cid, title != null ? title : "Chat");

                                            @SuppressWarnings("unchecked")
                                            List<Object> msgList = (List<Object>) cMap.get("messages");
                                            if (msgList != null) {
                                                for (Object mObj : msgList) {
                                                    if (mObj instanceof Map) {
                                                        @SuppressWarnings("unchecked")
                                                        Map<String, Object> mMap = (Map<String, Object>) mObj;
                                                        String sender = (String) mMap.get("sender");
                                                        String text = (String) mMap.get("text");
                                                        String imgPath = (String) mMap.get("image_path");
                                                        conv.addMessage(sender, text, imgPath);
                                                    }
                                                }
                                            }
                                            conversations.add(conv);
                                        }
                                    }
                                }
                                if (conversations.isEmpty()) {
                                    createNewChat();
                                } else {
                                    selectConversation(conversations.get(0));
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        if (conversations.isEmpty()) createNewChat();
                    }
                });
            })
            .exceptionally(ex -> {
                Platform.runLater(() -> {
                    if (conversations.isEmpty()) createNewChat();
                });
                return null;
            });
    }

    private void syncConversationToBackend(Conversation conv) {
        if (loggedInUserId == null || conv == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"user_id\":").append(loggedInUserId).append(",");
            sb.append("\"conversation_id\":").append(escapeJson(conv.getId())).append(",");
            sb.append("\"title\":").append(escapeJson(conv.getTitle())).append(",");
            sb.append("\"messages\":[");
            List<ChatMessage> msgs = conv.getMessages();
            for (int i = 0; i < msgs.size(); i++) {
                ChatMessage m = msgs.get(i);
                sb.append("{");
                sb.append("\"sender\":").append(escapeJson(m.getSender())).append(",");
                sb.append("\"text\":").append(escapeJson(m.getText())).append(",");
                sb.append("\"image_path\":").append(m.getImagePath() != null ? escapeJson(m.getImagePath()) : "null");
                sb.append("}");
                if (i < msgs.size() - 1) sb.append(",");
            }
            sb.append("]}");

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BACKEND_URL + "/conversations/save/"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString(), StandardCharsets.UTF_8))
                .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void deleteConversationFromBackend(String conversationId) {
        if (loggedInUserId == null || conversationId == null) return;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BACKEND_URL + "/conversations/" + conversationId))
                .DELETE()
                .build();
            client.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
    }

    // ==============================================================================
    // WINDOW DOCKING & SCREEN ACTIONS
    // ==============================================================================

    public void applyInitialDock() {
        if (stage == null) return;

        javafx.geometry.Rectangle2D visualBounds = javafx.stage.Screen.getPrimary().getVisualBounds();
        double screenWidth = visualBounds.getWidth();
        double screenHeight = visualBounds.getHeight();
        double dockWidth = Math.max(370, screenWidth * 0.28);
        int workspaceWidth = (int) (visualBounds.getMaxX() - dockWidth);
        int workspaceHeight = (int) screenHeight;

        standardX = Math.max(0, (screenWidth - 950) / 2);
        standardY = Math.max(0, (screenHeight - 650) / 2);
        standardWidth = 950;
        standardHeight = 650;

        stage.setX(visualBounds.getMaxX() - dockWidth);
        stage.setY(visualBounds.getMinY());
        stage.setWidth(dockWidth);
        stage.setHeight(screenHeight);
        stage.setAlwaysOnTop(false);
        isSideDocked = true;

        if (btnSideDock != null) {
            btnSideDock.setText("🗗 Standard");
            btnSideDock.setStyle("-fx-background-color: #1a73e8; -fx-text-fill: #ffffff; -fx-background-radius: 12; -fx-cursor: hand; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 4 8;");
        }

        // 1. Actively snap all background windows to left 72%
        autoSnapBackgroundAppToLeft(workspaceWidth, workspaceHeight);

        // 2. Tell OS to resize desktop work area so ALL apps reflow within left 72%
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BACKEND_URL + "/set-os-workarea/?dock_width=" + ((int) dockWidth)))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
            client.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
    }

    @FXML
    public void snapAllTabs() {
        if (stage == null) return;
        javafx.geometry.Rectangle2D visualBounds = javafx.stage.Screen.getPrimary().getVisualBounds();
        double screenWidth = visualBounds.getWidth();
        double screenHeight = visualBounds.getHeight();
        double dockWidth = Math.max(370, screenWidth * 0.28);
        int workspaceWidth = (int) (visualBounds.getMaxX() - dockWidth);
        int workspaceHeight = (int) screenHeight;

        autoSnapBackgroundAppToLeft(workspaceWidth, workspaceHeight);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BACKEND_URL + "/set-os-workarea/?dock_width=" + ((int) dockWidth)))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
            client.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
    }

    @FXML
    public void toggleSideDocking() {
        if (stage == null) return;
        if (!isSideDocked) {
            applyInitialDock();
        } else {
            // Restore to standard center window
            stage.setX(standardX > 0 ? standardX : 100);
            stage.setY(standardY > 0 ? standardY : 100);
            stage.setWidth(standardWidth > 0 ? standardWidth : 950);
            stage.setHeight(standardHeight > 0 ? standardHeight : 650);
            stage.setAlwaysOnTop(false);
            isSideDocked = false;

            // Restore Windows OS Work Area locally & via backend
            restoreLocalOsWorkArea();
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BACKEND_URL + "/restore-os-workarea/"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
                client.sendAsync(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {}

            if (btnSideDock != null) {
                btnSideDock.setText("🗔 Dock");
                btnSideDock.setStyle("-fx-background-color: #2b2c2f; -fx-text-fill: #c58af9; -fx-background-radius: 12; -fx-cursor: hand; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 4 8;");
            }
        }
    }

    public static void restoreLocalOsWorkArea() {
        new Thread(() -> {
            try {
                String psScript = "$code = @'\n"
                    + "using System;\n"
                    + "using System.Runtime.InteropServices;\n"
                    + "public class WorkAreaRestorer {\n"
                    + "    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }\n"
                    + "    [DllImport(\"user32.dll\")] public static extern int GetSystemMetrics(int nIndex);\n"
                    + "    [DllImport(\"user32.dll\")] public static extern bool SystemParametersInfo(uint uiAction, uint uiParam, ref RECT pvParam, uint fWinIni);\n"
                    + "    public static void Restore() {\n"
                    + "        RECT curr = new RECT();\n"
                    + "        SystemParametersInfo(0x0030, 0, ref curr, 0);\n"
                    + "        int screenW = GetSystemMetrics(0);\n"
                    + "        curr.Left = 0;\n"
                    + "        curr.Right = screenW;\n"
                    + "        SystemParametersInfo(0x002F, 0, ref curr, 0x0003);\n"
                    + "    }\n"
                    + "}\n"
                    + "'@\n"
                    + "Add-Type -TypeDefinition $code -ErrorAction SilentlyContinue\n"
                    + "[WorkAreaRestorer]::Restore()\n";
                byte[] encodedBytes = psScript.getBytes(StandardCharsets.UTF_16LE);
                String base64Cmd = Base64.getEncoder().encodeToString(encodedBytes);
                new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", base64Cmd).start();
            } catch (Exception ignored) {}
        }).start();
    }

    private void autoSnapBackgroundAppToLeft(int targetWidth, int targetHeight) {
        new Thread(() -> {
            try {
                long myPid = ProcessHandle.current().pid();
                String psScript = "$code = @'\n"
                    + "using System;\n"
                    + "using System.Runtime.InteropServices;\n"
                    + "using System.Text;\n"
                    + "using System.Collections.Generic;\n"
                    + "public class WindowSnapper {\n"
                    + "    [DllImport(\"user32.dll\")] public static extern bool SystemParametersInfo(uint uiAction, uint uiParam, ref RECT pvParam, uint fWinIni);\n"
                    + "    [DllImport(\"user32.dll\")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);\n"
                    + "    [DllImport(\"user32.dll\")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter, int X, int Y, int cx, int cy, uint uFlags);\n"
                    + "    [DllImport(\"user32.dll\")] public static extern bool MoveWindow(IntPtr hWnd, int X, int Y, int nWidth, int nHeight, bool bRepaint);\n"
                    + "    [DllImport(\"user32.dll\")] public static extern IntPtr SendMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);\n"
                    + "    [DllImport(\"user32.dll\")] public static extern bool IsWindowVisible(IntPtr hWnd);\n"
                    + "    [DllImport(\"user32.dll\")] public static extern bool IsIconic(IntPtr hWnd);\n"
                    + "    [DllImport(\"user32.dll\")] public static extern bool IsZoomed(IntPtr hWnd);\n"
                    + "    [DllImport(\"user32.dll\")] public static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);\n"
                    + "    [DllImport(\"user32.dll\")] public static extern int GetWindowTextLength(IntPtr hWnd);\n"
                    + "    [DllImport(\"user32.dll\")] public static extern int GetClassName(IntPtr hWnd, StringBuilder lpString, int nMaxCount);\n"
                    + "    [DllImport(\"user32.dll\")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);\n"
                    + "    [DllImport(\"user32.dll\")] public static extern IntPtr GetShellWindow();\n"
                    + "    [DllImport(\"user32.dll\")] public static extern int GetWindowLong(IntPtr hWnd, int nIndex);\n"
                    + "    [DllImport(\"user32.dll\")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);\n"
                    + "    [DllImport(\"user32.dll\")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);\n"
                    + "    [DllImport(\"user32.dll\")] public static extern bool GetWindowPlacement(IntPtr hWnd, ref WINDOWPLACEMENT lpwndpl);\n"
                    + "    [DllImport(\"user32.dll\")] public static extern bool SetWindowPlacement(IntPtr hWnd, ref WINDOWPLACEMENT lpwndpl);\n"
                    + "    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);\n"
                    + "    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X; public int Y; }\n"
                    + "    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }\n"
                    + "    [StructLayout(LayoutKind.Sequential)] public struct WINDOWPLACEMENT {\n"
                    + "        public int length;\n"
                    + "        public int flags;\n"
                    + "        public int showCmd;\n"
                    + "        public POINT ptMinPosition;\n"
                    + "        public POINT ptMaxPosition;\n"
                    + "        public RECT rcNormalPosition;\n"
                    + "    }\n"
                    + "    private static bool ShouldSkip(IntPtr hWnd, IntPtr shell, uint myPid, out string title) {\n"
                    + "        title = \"\";\n"
                    + "        try {\n"
                    + "            if (hWnd == shell || !IsWindowVisible(hWnd) || IsIconic(hWnd)) return true;\n"
                    + "            int len = GetWindowTextLength(hWnd);\n"
                    + "            if (len == 0) return true;\n"
                    + "            StringBuilder sb = new StringBuilder(len + 1);\n"
                    + "            GetWindowText(hWnd, sb, len + 1);\n"
                    + "            title = sb.ToString();\n"
                    + "            if (title.StartsWith(\"SnipTutor - Universal AI Side-Workflow\", StringComparison.OrdinalIgnoreCase)) return true;\n"
                    + "            uint winPid = 0;\n"
                    + "            GetWindowThreadProcessId(hWnd, out winPid);\n"
                    + "            if (winPid == myPid && title.Contains(\"SnipTutor - Universal AI\")) return true;\n"
                    + "            StringBuilder sbCls = new StringBuilder(256);\n"
                    + "            GetClassName(hWnd, sbCls, 256);\n"
                    + "            string cls = sbCls.ToString();\n"
                    + "            if (cls == \"Progman\" || cls == \"Shell_TrayWnd\" || cls == \"Shell_SecondaryTrayWnd\") return true;\n"
                    + "            if (title == \"Program Manager\" || title == \"Windows Input Experience\" || title == \"Settings\") return true;\n"
                    + "            int style = GetWindowLong(hWnd, -16);\n"
                    + "            if ((style & 0x40000000) != 0) return true;\n"
                    + "            RECT r;\n"
                    + "            GetWindowRect(hWnd, out r);\n"
                    + "            if ((r.Right - r.Left) < 50 || (r.Bottom - r.Top) < 50) return true;\n"
                    + "            return false;\n"
                    + "        } catch { return true; }\n"
                    + "    }\n"
                    + "    private static void ResizeOneWindow(IntPtr hWnd, int targetW, int targetH) {\n"
                    + "        try {\n"
                    + "            WINDOWPLACEMENT wp = new WINDOWPLACEMENT();\n"
                    + "            wp.length = Marshal.SizeOf(wp);\n"
                    + "            GetWindowPlacement(hWnd, ref wp);\n"
                    + "            if (IsZoomed(hWnd) || wp.showCmd == 3) {\n"
                    + "                wp.showCmd = 4;\n"
                    + "                wp.rcNormalPosition.Left = 0;\n"
                    + "                wp.rcNormalPosition.Top = 0;\n"
                    + "                wp.rcNormalPosition.Right = targetW;\n"
                    + "                wp.rcNormalPosition.Bottom = targetH;\n"
                    + "                SetWindowPlacement(hWnd, ref wp);\n"
                    + "                SendMessage(hWnd, 0x0112, (IntPtr)0xF120, IntPtr.Zero);\n"
                    + "                ShowWindow(hWnd, 4);\n"
                    + "            }\n"
                    + "            MoveWindow(hWnd, 0, 0, targetW, targetH, true);\n"
                    + "            SetWindowPos(hWnd, IntPtr.Zero, 0, 0, targetW, targetH, 0x0054);\n"
                    + "        } catch {}\n"
                    + "    }\n"
                    + "    public static void SnapAll(int targetW, int targetH, uint myPid) {\n"
                    + "        try {\n"
                    + "            RECT wa = new RECT();\n"
                    + "            SystemParametersInfo(0x0030, 0, ref wa, 0);\n"
                    + "            wa.Right = targetW;\n"
                    + "            SystemParametersInfo(0x002F, 0, ref wa, 0x0003);\n"
                    + "        } catch {}\n"
                    + "        IntPtr shell = GetShellWindow();\n"
                    + "        EnumWindows((hWnd, lParam) => {\n"
                    + "            string title;\n"
                    + "            if (!ShouldSkip(hWnd, shell, myPid, out title)) {\n"
                    + "                ResizeOneWindow(hWnd, targetW, targetH);\n"
                    + "            }\n"
                    + "            return true;\n"
                    + "        }, IntPtr.Zero);\n"
                    + "        System.Threading.Thread.Sleep(90);\n"
                    + "        EnumWindows((hWnd, lParam) => {\n"
                    + "            string title;\n"
                    + "            if (!ShouldSkip(hWnd, shell, myPid, out title)) {\n"
                    + "                ResizeOneWindow(hWnd, targetW, targetH);\n"
                    + "            }\n"
                    + "            return true;\n"
                    + "        }, IntPtr.Zero);\n"
                    + "        System.Threading.Thread.Sleep(150);\n"
                    + "        EnumWindows((hWnd, lParam) => {\n"
                    + "            string title;\n"
                    + "            if (!ShouldSkip(hWnd, shell, myPid, out title)) {\n"
                    + "                ResizeOneWindow(hWnd, targetW, targetH);\n"
                    + "            }\n"
                    + "            return true;\n"
                    + "        }, IntPtr.Zero);\n"
                    + "    }\n"
                    + "}\n"
                    + "'@\n"
                    + "Add-Type -TypeDefinition $code -ErrorAction SilentlyContinue\n"
                    + "[WindowSnapper]::SnapAll(" + targetWidth + ", " + targetHeight + ", " + myPid + ")\n";

                byte[] encodedBytes = psScript.getBytes(StandardCharsets.UTF_16LE);
                String base64Cmd = Base64.getEncoder().encodeToString(encodedBytes);

                ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", base64Cmd);
                pb.start();
            } catch (Exception ignored) {}
        }).start();
    }

    @FXML
    public void captureFullActiveScreen() {
        try {
            boolean wasAlwaysOnTop = (stage != null && stage.isAlwaysOnTop());
            if (stage != null) {
                stage.setOpacity(0.0);
            }
            new Thread(() -> {
                try {
                    Thread.sleep(200); // brief moment to let OS render underlying screen
                    java.awt.Robot robot = new java.awt.Robot();
                    javafx.geometry.Rectangle2D screenBounds = javafx.stage.Screen.getPrimary().getBounds();
                    java.awt.Rectangle area = new java.awt.Rectangle(
                        (int) screenBounds.getMinX(),
                        (int) screenBounds.getMinY(),
                        (int) screenBounds.getWidth(),
                        (int) screenBounds.getHeight()
                    );
                    java.awt.image.BufferedImage screenCapture = robot.createScreenCapture(area);

                    String userHome = System.getProperty("user.home");
                    java.io.File snipDir = new java.io.File(userHome, "Pictures/SnipTutor");
                    if (!snipDir.exists()) snipDir.mkdirs();

                    String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
                    java.io.File archivedFile = new java.io.File(snipDir, "ScreenContext_" + timestamp + ".png");
                    javax.imageio.ImageIO.write(screenCapture, "png", archivedFile);

                    // Auto-copy to OS clipboard
                    try {
                        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new TransferableImage(screenCapture), null);
                    } catch (Exception ignored) {}

                    Platform.runLater(() -> {
                        if (stage != null) {
                            stage.setOpacity(1.0);
                            if (wasAlwaysOnTop) stage.setAlwaysOnTop(true);
                        }
                        attachFile(archivedFile);
                        userInput.requestFocus();
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        if (stage != null) stage.setOpacity(1.0);
                    });
                    e.printStackTrace();
                }
            }).start();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @FXML
    public void activateSnippingTool() {
        SnippingOverlay overlay = new SnippingOverlay(capturedFile -> {
            attachFile(capturedFile);
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
        if (attachedFilePath != null && Files.exists(attachedFilePath)) {
            return attachedFilePath;
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
