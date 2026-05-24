package com.bot.controller;

import com.bot.model.AssistantChatRequest;
import com.bot.model.AssistantChatResponse;
import com.bot.service.AssistantConversationService;
import com.bot.service.NewsCardRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantControllerTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void chatBuildsMediaUrlFromCurrentRequest() {
        AssistantConversationService service = mock(AssistantConversationService.class);
        NewsCardRenderer renderer = mock(NewsCardRenderer.class);
        AssistantController controller = new AssistantController(service, renderer);

        AssistantChatResponse serviceResponse = AssistantChatResponse.builder()
                .reply("ok")
                .mediaType("image")
                .mediaPath("C:/temp/news-card-1.png")
                .build();
        when(service.chat(any(AssistantChatRequest.class))).thenReturn(serviceResponse);
        when(renderer.buildCardUrl(eq("C:/temp/news-card-1.png"), eq("http://localhost:8080")))
                .thenReturn("http://localhost:8080/api/media/cards/news-card-1.png");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8080);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        AssistantChatResponse response = controller.chat(new AssistantChatRequest());

        assertEquals("http://localhost:8080/api/media/cards/news-card-1.png", response.getMediaUrl());
    }
}