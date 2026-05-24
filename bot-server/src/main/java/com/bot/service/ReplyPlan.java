package com.bot.service;

import com.bot.model.NewsItem;

import java.util.List;

record ReplyPlan(String reply, List<NewsItem> newsSnapshot, String mediaType, String mediaPath, String mediaCaption) {
    static ReplyPlan of(String reply) {
        return new ReplyPlan(reply, List.of(), null, null, null);
    }
}