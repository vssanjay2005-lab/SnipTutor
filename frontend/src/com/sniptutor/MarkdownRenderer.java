package com.sniptutor;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class MarkdownRenderer {

    public static VBox render(String markdownText, boolean isUser) {
        return render(markdownText, isUser, null);
    }

    public static VBox render(String markdownText, boolean isUser, Consumer<String> onFollowUpClick) {
        VBox root = new VBox(8);
        root.setMaxWidth(Double.MAX_VALUE);
        root.setFillWidth(true);

        if (markdownText == null || markdownText.trim().isEmpty()) {
            return root;
        }

        String[] lines = markdownText.split("\r?\n");
        int i = 0;
        boolean inFollowUpSection = false;
        VBox followUpContainer = null;
        FlowPane followUpChipsPane = null;

        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            // Check if this line starts a follow-up questions section
            String lower = trimmed.toLowerCase();
            boolean isFollowUpHeader = (lower.contains("follow-up") || lower.contains("follow up") || lower.contains("suggested next") || lower.contains("explore further"))
                    && (trimmed.startsWith("#") || trimmed.startsWith("**") || trimmed.endsWith(":") || trimmed.contains("💬"));

            if (isFollowUpHeader && !isUser) {
                inFollowUpSection = true;
                
                followUpChipsPane = new FlowPane(8, 8);
                followUpChipsPane.setPrefWrapLength(260);
                followUpChipsPane.setMaxWidth(Double.MAX_VALUE);
                followUpChipsPane.setStyle("-fx-padding: 6 0 2 0;");
                
                root.getChildren().add(followUpChipsPane);
                i++;
                continue;
            }

            // If we are currently collecting follow-up questions
            if (inFollowUpSection) {
                if (trimmed.startsWith("* ") || trimmed.startsWith("- ") || trimmed.startsWith("• ") || trimmed.matches("^\\d+\\.\\s+.*")) {
                    String rawQuestion = trimmed.replaceAll("^[\\*\\-•\\d\\.]+\\s*", "").trim();
                    if (!rawQuestion.isEmpty()) {
                        Node chip = createFollowUpChip(rawQuestion, onFollowUpClick, root);
                        if (followUpChipsPane != null) {
                            followUpChipsPane.getChildren().add(chip);
                        }
                    }
                    i++;
                    continue;
                } else if (trimmed.startsWith("#")) {
                    inFollowUpSection = false;
                } else if (trimmed.isEmpty()) {
                    i++;
                    continue;
                }
            }

            // 1. Code Block Fence (```lang ... ```)
            if (trimmed.startsWith("```")) {
                String lang = trimmed.substring(3).trim();
                if (lang.isEmpty()) lang = "CODE";
                StringBuilder codeContent = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    if (codeContent.length() > 0) {
                        codeContent.append("\n");
                    }
                    codeContent.append(lines[i]);
                    i++;
                }
                if (i < lines.length) i++; // skip closing ```

                String cleanCode = codeContent.toString()
                        .replaceAll("^[\\r\\n]+", "")
                        .replaceAll("[\\s\\r\\n]+$", "");
                root.getChildren().add(createCodeBlock(lang.toUpperCase(), cleanCode, root));
                continue;
            }

            // 2. Headers (#, ##, ###, ####)
            if (trimmed.startsWith("### ")) {
                root.getChildren().add(createHeaderLabel(trimmed.substring(4).trim(), 13.5, "#c58af9", true, root));
                i++;
                continue;
            } else if (trimmed.startsWith("## ")) {
                root.getChildren().add(createHeaderLabel(trimmed.substring(3).trim(), 14.5, "#8ab4f8", true, root));
                i++;
                continue;
            } else if (trimmed.startsWith("# ")) {
                root.getChildren().add(createHeaderLabel(trimmed.substring(2).trim(), 16.0, "#ffffff", true, root));
                i++;
                continue;
            }

            // 3. Bullet points (*, -, •)
            if (trimmed.startsWith("* ") || trimmed.startsWith("- ") || trimmed.startsWith("• ")) {
                String bulletText = trimmed.substring(2).trim();
                root.getChildren().add(createBulletItem("•", bulletText, isUser, root));
                i++;
                continue;
            }

            // 4. Numbered list (1. 2. etc)
            if (trimmed.matches("^\\d+\\.\\s+.*")) {
                int dotIndex = trimmed.indexOf('.');
                String num = trimmed.substring(0, dotIndex + 1);
                String rest = trimmed.substring(dotIndex + 1).trim();
                root.getChildren().add(createBulletItem(num, rest, isUser, root));
                i++;
                continue;
            }

            // 5. Empty line (Spacing)
            if (trimmed.isEmpty()) {
                Region spacer = new Region();
                spacer.setPrefHeight(2);
                root.getChildren().add(spacer);
                i++;
                continue;
            }

            // 6. Regular Paragraph with inline bold/italic parsing
            root.getChildren().add(createFormattedParagraph(trimmed, isUser, root));
            i++;
        }

        return root;
    }

    private static Node createFollowUpChip(String questionText, Consumer<String> onFollowUpClick, VBox root) {
        String cleanText = questionText.replaceAll("\\[([^\\]]+)\\]\\([^\\)]+\\)", "$1")
                                       .replaceAll("^\\[|\\]$", "")
                                       .replace("**", "")
                                       .replace("__", "")
                                       .trim();
        
        String icon = "💡 ";
        String promptQuery = cleanText;
        if (cleanText.startsWith("💡") || cleanText.startsWith("🔍") || cleanText.startsWith("⚡") || cleanText.startsWith("🔹") || cleanText.startsWith("💬") || cleanText.startsWith("❓")) {
            icon = cleanText.substring(0, 2) + " ";
            promptQuery = cleanText.substring(2).trim();
        } else if (cleanText.startsWith("[")) {
            cleanText = cleanText.replaceAll("^\\[|\\]$", "");
            promptQuery = cleanText;
        }

        final String finalPrompt = promptQuery;

        Button chip = new Button(icon + promptQuery);
        chip.setWrapText(true);
        chip.setMaxWidth(Double.MAX_VALUE);
        if (root != null) {
            chip.prefWidthProperty().bind(root.widthProperty().subtract(12));
        }
        chip.setAlignment(Pos.CENTER_LEFT);
        
        String defaultStyle = "-fx-background-color: #1e1f20; " +
                              "-fx-text-fill: #8ab4f8; " +
                              "-fx-font-family: 'Segoe UI', sans-serif; " +
                              "-fx-font-size: 12px; " +
                              "-fx-padding: 6 12; " +
                              "-fx-background-radius: 14px; " +
                              "-fx-border-color: #3c4043; " +
                              "-fx-border-radius: 14px; " +
                              "-fx-border-width: 1px; " +
                              "-fx-cursor: hand;";

        String hoverStyle = "-fx-background-color: #2b2c2f; " +
                            "-fx-text-fill: #d2e3fc; " +
                            "-fx-font-family: 'Segoe UI', sans-serif; " +
                            "-fx-font-size: 12px; " +
                            "-fx-padding: 6 12; " +
                            "-fx-background-radius: 14px; " +
                            "-fx-border-color: #8ab4f8; " +
                            "-fx-border-radius: 14px; " +
                            "-fx-border-width: 1px; " +
                            "-fx-cursor: hand;";

        chip.setStyle(defaultStyle);
        chip.setOnMouseEntered(e -> chip.setStyle(hoverStyle));
        chip.setOnMouseExited(e -> chip.setStyle(defaultStyle));

        chip.setOnAction(e -> {
            if (onFollowUpClick != null) {
                onFollowUpClick.accept(finalPrompt);
            }
        });

        return chip;
    }

    private static Label createHeaderLabel(String text, double fontSize, String colorHex, boolean isBold, VBox root) {
        // Strip out any trailing markdown formatting in headers like **Header**
        String cleanText = text.replace("**", "").replace("__", "").trim();
        Label label = new Label(cleanText);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.prefWidthProperty().bind(root.widthProperty().subtract(6));
        label.setStyle("-fx-text-fill: " + colorHex + "; " +
                       "-fx-font-size: " + fontSize + "px; " +
                       "-fx-font-weight: " + (isBold ? "bold" : "normal") + "; " +
                       "-fx-padding: 4 0 2 0;");
        return label;
    }

    private static HBox createBulletItem(String marker, String content, boolean isUser, VBox root) {
        HBox row = new HBox(6);
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(1, 0, 1, 4));
        row.setMaxWidth(Double.MAX_VALUE);
        row.prefWidthProperty().bind(root.widthProperty().subtract(6));

        Label markerLabel = new Label(marker);
        markerLabel.setStyle("-fx-text-fill: " + (isUser ? "#8ab4f8" : "#c58af9") + "; -fx-font-size: 13px; -fx-font-weight: bold;");

        TextFlow flow = createInlineTextFlow(content, isUser);
        HBox.setHgrow(flow, Priority.ALWAYS);
        flow.prefWidthProperty().bind(root.widthProperty().subtract(28));
        flow.maxWidthProperty().bind(root.widthProperty().subtract(28));

        row.getChildren().addAll(markerLabel, flow);
        return row;
    }

    private static TextFlow createFormattedParagraph(String text, boolean isUser, VBox root) {
        TextFlow flow = createInlineTextFlow(text, isUser);
        flow.setPadding(new Insets(1, 0, 1, 0));
        flow.prefWidthProperty().bind(root.widthProperty().subtract(6));
        flow.maxWidthProperty().bind(root.widthProperty().subtract(6));
        return flow;
    }

    private static TextFlow createInlineTextFlow(String text, boolean isUser) {
        TextFlow flow = new TextFlow();
        flow.setLineSpacing(3.5);

        // Parse inline **bold** and `code`
        List<TextSegment> segments = parseInlineSegments(text);
        for (TextSegment seg : segments) {
            Text textNode = new Text(seg.content);
            if (seg.isCode) {
                textNode.setFont(Font.font("Consolas", 12));
                textNode.setFill(Color.web("#93c5fd"));
            } else if (seg.isBold) {
                textNode.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
                textNode.setFill(Color.web(isUser ? "#ffffff" : "#f1f3f4"));
            } else {
                textNode.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 13));
                textNode.setFill(Color.web(isUser ? "#e3e3e3" : "#d1d5db"));
            }
            flow.getChildren().add(textNode);
        }

        return flow;
    }

    private static VBox createCodeBlock(String language, String code, VBox root) {
        VBox container = new VBox(0);
        container.setMaxWidth(Double.MAX_VALUE);
        container.prefWidthProperty().bind(root.widthProperty().subtract(6));
        container.setStyle("-fx-background-color: #0d1117; " +
                           "-fx-background-radius: 8px; " +
                           "-fx-border-color: #30363d; " +
                           "-fx-border-radius: 8px; " +
                           "-fx-border-width: 1px; " +
                           "-fx-margin: 4 0 4 0;");

        // Code block Top Header with Language tag and Copy Button
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #161b22; -fx-padding: 5 10; -fx-background-radius: 7 7 0 0; -fx-border-color: #30363d; -fx-border-width: 0 0 1 0;");

        Label langLabel = new Label("⚙️ " + language);
        langLabel.setStyle("-fx-text-fill: #8b949e; -fx-font-size: 10.5px; -fx-font-weight: bold; -fx-font-family: 'Segoe UI';");

        Region filler = new Region();
        HBox.setHgrow(filler, Priority.ALWAYS);

        Button copyBtn = new Button("📋 Copy Code");
        copyBtn.setTooltip(new Tooltip("Copy code block to clipboard"));
        copyBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #c9d1d9; -fx-font-size: 10px; -fx-cursor: hand; -fx-padding: 2 6; -fx-border-color: #30363d; -fx-border-radius: 4;");

        copyBtn.setOnAction(e -> {
            try {
                Clipboard clipboard = Clipboard.getSystemClipboard();
                ClipboardContent content = new ClipboardContent();
                content.putString(code);
                clipboard.setContent(content);

                copyBtn.setText("✅ Copied!");
                copyBtn.setStyle("-fx-background-color: #238636; -fx-text-fill: #ffffff; -fx-font-size: 10px; -fx-font-weight: bold; -fx-padding: 2 6; -fx-background-radius: 4;");
                new Thread(() -> {
                    try { Thread.sleep(1800); } catch (Exception ignored) {}
                    Platform.runLater(() -> {
                        copyBtn.setText("📋 Copy Code");
                        copyBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #c9d1d9; -fx-font-size: 10px; -fx-cursor: hand; -fx-padding: 2 6; -fx-border-color: #30363d; -fx-border-radius: 4;");
                    });
                }).start();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        header.getChildren().addAll(langLabel, filler, copyBtn);

        // Code Body with internal horizontal scroll support inside the black division
        Text codeText = new Text(code);
        codeText.setFill(Color.web("#e6edf3"));
        codeText.setFont(Font.font("Consolas", FontWeight.NORMAL, 12.5));
        codeText.setLineSpacing(3.0);

        StackPane codeBodyBox = new StackPane(codeText);
        StackPane.setAlignment(codeText, Pos.TOP_LEFT);
        codeBodyBox.setPadding(new Insets(12, 16, 18, 12));
        codeBodyBox.setStyle("-fx-background-color: #0d1117; -fx-background-radius: 0 0 7 7;");
        codeBodyBox.setMinHeight(Region.USE_PREF_SIZE);
        codeBodyBox.setMinWidth(Region.USE_PREF_SIZE);

        ScrollPane codeScrollPane = new ScrollPane();
        codeScrollPane.getStyleClass().add("code-scroll-pane");
        codeScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        codeScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        codeScrollPane.setFitToWidth(false);
        codeScrollPane.setFitToHeight(false);
        codeScrollPane.setStyle("-fx-background: #0d1117; -fx-background-color: #0d1117; -fx-border-color: transparent; -fx-padding: 0 0 4 0; -fx-background-radius: 0 0 7 7;");

        codeScrollPane.setContent(codeBodyBox);
        codeScrollPane.prefViewportHeightProperty().bind(codeBodyBox.heightProperty());
        codeScrollPane.minHeightProperty().bind(codeBodyBox.heightProperty().add(10));
        codeScrollPane.prefHeightProperty().bind(codeBodyBox.heightProperty().add(10));

        container.getChildren().addAll(header, codeScrollPane);
        return container;
    }

    private static List<TextSegment> parseInlineSegments(String text) {
        List<TextSegment> segments = new ArrayList<>();
        if (text == null || text.isEmpty()) return segments;

        int i = 0;
        int len = text.length();
        StringBuilder cur = new StringBuilder();

        while (i < len) {
            // Bold **text**
            if (i + 1 < len && text.charAt(i) == '*' && text.charAt(i + 1) == '*') {
                if (cur.length() > 0) {
                    segments.add(new TextSegment(cur.toString(), false, false));
                    cur.setLength(0);
                }
                int end = text.indexOf("**", i + 2);
                if (end != -1) {
                    String boldContent = text.substring(i + 2, end);
                    segments.add(new TextSegment(boldContent, true, false));
                    i = end + 2;
                    continue;
                }
            }

            // Inline `code`
            if (text.charAt(i) == '`') {
                if (cur.length() > 0) {
                    segments.add(new TextSegment(cur.toString(), false, false));
                    cur.setLength(0);
                }
                int end = text.indexOf('`', i + 1);
                if (end != -1) {
                    String codeContent = text.substring(i + 1, end);
                    segments.add(new TextSegment(codeContent, false, true));
                    i = end + 1;
                    continue;
                }
            }

            cur.append(text.charAt(i));
            i++;
        }

        if (cur.length() > 0) {
            segments.add(new TextSegment(cur.toString(), false, false));
        }

        return segments;
    }

    private static class TextSegment {
        String content;
        boolean isBold;
        boolean isCode;

        TextSegment(String content, boolean isBold, boolean isCode) {
            this.content = content;
            this.isBold = isBold;
            this.isCode = isCode;
        }
    }
}
