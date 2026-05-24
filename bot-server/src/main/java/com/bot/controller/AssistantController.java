package com.bot.controller;

import com.bot.model.AssistantChatRequest;
import com.bot.model.AssistantChatResponse;
import com.bot.service.AssistantConversationService;
import com.bot.service.NewsCardRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import static com.bot.util.TextUtils.hasText;

@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantConversationService assistantConversationService;
    private final NewsCardRenderer newsCardRenderer;

    @PostMapping("/chat")
    public AssistantChatResponse chat(@RequestBody AssistantChatRequest request) {
        AssistantChatResponse response = assistantConversationService.chat(request);
        if (hasText(response.getMediaPath()) && !hasText(response.getMediaUrl())) {
            String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                    .build()
                    .toUriString();
            response.setMediaUrl(newsCardRenderer.buildCardUrl(response.getMediaPath(), baseUrl));
        }
        return response;
    }
}