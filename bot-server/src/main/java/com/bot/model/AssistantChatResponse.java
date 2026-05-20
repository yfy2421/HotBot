package com.bot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssistantChatResponse {
    private String conversationId;
    private String scene;
    private String targetId;
    private String reply;
    private String mediaType;
    private String mediaUrl;
    private String mediaPath;
    private String mediaCaption;
    private boolean sentToQq;
    private List<NewsItem> newsSnapshot;
}