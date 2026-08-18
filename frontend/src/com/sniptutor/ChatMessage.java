package com.sniptutor;

import java.io.Serializable;

public class ChatMessage implements Serializable {
    private String sender;
    private String text;
    private String imagePath;

    public ChatMessage(String sender, String text, String imagePath) {
        this.sender = sender;
        this.text = text;
        this.imagePath = imagePath;
    }

    public String getSender() { return sender; }
    public String getText() { return text; }
    public String getImagePath() { return imagePath; }
}