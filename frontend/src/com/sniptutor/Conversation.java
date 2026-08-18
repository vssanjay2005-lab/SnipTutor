package com.sniptutor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Conversation implements Serializable {
    private String id;
    private String title;
    private List<ChatMessage> messages;

    public Conversation(String title) {
        this.id = UUID.randomUUID().toString();
        this.title = title;
        this.messages = new ArrayList<>();
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public List<ChatMessage> getMessages() { return messages; }

    public void addMessage(String sender, String text, String imagePath) {
        this.messages.add(new ChatMessage(sender, text, imagePath));
    }
}