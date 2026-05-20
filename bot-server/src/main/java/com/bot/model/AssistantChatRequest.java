package com.bot.model;

import lombok.Data;

@Data
public class AssistantChatRequest {
    private String conversationId;
    private String scene;
    private String senderId;
    private String chatId;
    private String msgId;
    private String content;
    private boolean sendReply;
}