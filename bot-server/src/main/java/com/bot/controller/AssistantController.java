package com.bot.controller;

import com.bot.model.AssistantChatRequest;
import com.bot.model.AssistantChatResponse;
import com.bot.service.AssistantConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantConversationService assistantConversationService;

    @PostMapping("/chat")
    public AssistantChatResponse chat(@RequestBody AssistantChatRequest request) {
        return assistantConversationService.chat(request);
    }
}