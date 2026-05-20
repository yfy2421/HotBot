package com.bot.service;

import com.bot.model.JokeItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.Random;

@Slf4j
@Service
public class JokeService {

    private final ObjectMapper mapper;
    private final Random random = new Random();
    private List<JokeItem> jokes;

    public JokeService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void load() {
        try {
            InputStream is = getClass().getClassLoader().getResourceAsStream("jokes.json");
            if (is != null) {
                jokes = mapper.readValue(is, new TypeReference<List<JokeItem>>() {});
                log.info("Loaded {} jokes from jokes.json", jokes.size());
                return;
            }
        } catch (Exception e) {
            log.warn("Failed to load jokes.json: {}", e.getMessage());
        }
        // Fallback
        jokes = List.of(
                JokeItem.builder().content("程序员最讨厌两件事：1. 写注释 2. 别人不写注释").source("网络").build(),
                JokeItem.builder().content("产品经理：这个需求很简单。程序员：那你来实现。产品经理：我不会。程序员：那就别说话。").source("网络").build()
        );
    }

    public JokeItem randomJoke() {
        return jokes.get(random.nextInt(jokes.size()));
    }
}
