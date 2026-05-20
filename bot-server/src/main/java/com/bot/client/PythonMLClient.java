package com.bot.client;

import com.bot.config.AppConfig;
import com.bot.model.AssistantMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class PythonMLClient {

    private final RestTemplate restTemplate;
    private final AppConfig.BotConfig config;

    private String url(String path) {
        return config.getMlServerUrl() + path;
    }

    /** Encode texts to embedding vectors. */
    public List<List<Float>> embed(List<String> texts) {
        var body = Map.of("texts", texts);
        var resp = restTemplate.postForObject(url("/api/embed"), body, Map.class);
        @SuppressWarnings("unchecked")
        var vectors = (List<List<Double>>) resp.get("vectors");
        return vectors.stream()
                .map(v -> v.stream().map(Double::floatValue).toList())
                .toList();
    }

    /** Generate AI commentary for a news article. */
    public String commentary(String content) {
        var body = Map.of("content", content);
        var resp = restTemplate.postForObject(url("/api/commentary"), body, Map.class);
        return (String) resp.get("commentary");
    }

    /** Generic assistant chat. */
    public String chat(String message, List<AssistantMessage> history, String systemPrompt) {
        var body = new HashMap<String, Object>();
        body.put("message", message);
        body.put("history", history.stream()
                .map(item -> Map.of("role", item.getRole(), "content", item.getContent()))
                .toList());
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system_prompt", systemPrompt);
        }
        var resp = restTemplate.postForObject(url("/api/chat"), body, Map.class);
        return (String) resp.get("reply");
    }

    /** Analyze sentiment of comments. Returns {positive, negative, neutral, summary}. */
    public Map<String, Object> sentiment(List<String> comments) {
        var body = Map.of("comments", comments);
        var resp = restTemplate.postForObject(url("/api/sentiment"), body, Map.class);
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) resp;
        return result;
    }

    /** Extract named entities from text. */
    public List<Map<String, Object>> ner(String text) {
        var body = Map.of("text", text);
        var resp = restTemplate.postForObject(url("/api/ner"), body, Map.class);
        @SuppressWarnings("unchecked")
        var entities = (List<Map<String, Object>>) resp.get("entities");
        return entities;
    }

    /** Store a news vector in ChromaDB. */
    public void addNews(String id, String text, List<Float> vector, Map<String, Object> metadata) {
        var body = Map.of("id", id, "text", text, "vector", vector, "metadata", metadata);
        restTemplate.postForObject(url("/api/news/add"), body, Map.class);
    }

    /** Query similar news vectors from ChromaDB. */
    public List<Map<String, Object>> querySimilar(List<Float> vector, int days, double threshold) {
        var body = Map.of("vector", vector, "days", days, "threshold", threshold);
        var resp = restTemplate.postForObject(url("/api/news/similar"), body, Map.class);
        @SuppressWarnings("unchecked")
        var matches = (List<Map<String, Object>>) resp.get("matches");
        return matches;
    }

    /** Store an entity vector in ChromaDB. */
    public void addEntity(String id, String name, String type, List<Float> vector, Map<String, Object> metadata) {
        var body = Map.of("id", id, "name", name, "type", type, "vector", vector, "metadata", metadata);
        restTemplate.postForObject(url("/api/entity/add"), body, Map.class);
    }

    /** Query historical entity vectors from ChromaDB. */
    public List<Map<String, Object>> queryEntityHistory(List<Float> vector, double threshold) {
        var body = Map.of("vector", vector, "threshold", threshold);
        var resp = restTemplate.postForObject(url("/api/entity/history"), body, Map.class);
        @SuppressWarnings("unchecked")
        var matches = (List<Map<String, Object>>) resp.get("matches");
        return matches;
    }
}
