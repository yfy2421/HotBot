package com.bot.client;

import com.bot.config.AppConfig;
import com.bot.model.AssistantMessage;
import com.bot.model.FetchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public FetchResult<String> commentaryWithAlert(String content,
                                                   String alertSource,
                                                   String alertCode,
                                                   String alertSubject) {
        return FetchResult.of(commentary(content));
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

    /** Rank semantic candidates for current-session title selection. */
    public List<SemanticRankMatch> rankCandidates(String query, List<String> candidates, int topK) {
        var body = new HashMap<String, Object>();
        body.put("query", query);
        body.put("candidates", candidates);
        body.put("top_k", topK);
        var resp = restTemplate.postForObject(url("/api/semantic/rank"), body, Map.class);
        if (resp == null || resp.get("matches") == null) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        var matches = (List<Map<String, Object>>) resp.get("matches");
        List<SemanticRankMatch> ranked = new ArrayList<>();
        for (Map<String, Object> match : matches) {
            ranked.add(new SemanticRankMatch(
                    readInt(match.get("index")),
                    (String) match.getOrDefault("candidate", ""),
                    readDouble(match.get("score")),
                    readDouble(match.get("embed_score")),
                    readDouble(match.get("rerank_score"))
            ));
        }
        return ranked;
    }

    /** Translate short news texts locally on the ml-server. */
    public List<String> translateTexts(List<String> texts, String textType) {
        var body = new HashMap<String, Object>();
        body.put("texts", texts);
        body.put("text_type", textType);
        var resp = restTemplate.postForObject(url("/api/translate"), body, Map.class);
        if (resp == null || resp.get("translations") == null) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        var translations = (List<Object>) resp.get("translations");
        return translations.stream().map(item -> item == null ? "" : item.toString()).toList();
    }

    /** Analyze sentiment of comments. Returns {positive, negative, neutral, summary}. */
    public Map<String, Object> sentiment(List<String> comments) {
        var body = Map.of("comments", comments);
        var resp = restTemplate.postForObject(url("/api/sentiment"), body, Map.class);
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) resp;
        return result;
    }

    public FetchResult<Map<String, Object>> sentimentWithAlert(List<String> comments,
                                                               String alertSource,
                                                               String alertCode,
                                                               String alertSubject) {
        return FetchResult.of(sentiment(comments));
    }

    /** Extract named entities from text. */
    public List<Map<String, Object>> ner(String text) {
        var body = Map.of("text", text);
        var resp = restTemplate.postForObject(url("/api/ner"), body, Map.class);
        @SuppressWarnings("unchecked")
        var entities = (List<Map<String, Object>>) resp.get("entities");
        return entities;
    }

    public FetchResult<List<Map<String, Object>>> nerWithAlert(String text,
                                                               String alertSource,
                                                               String alertCode,
                                                               String alertSubject) {
        return FetchResult.of(ner(text));
    }

    /** Store a news vector in ChromaDB. */
    public void addNews(String id, String text, List<Float> vector, Map<String, Object> metadata) {
        var body = Map.of("id", id, "text", text, "vector", vector, "metadata", metadata);
        restTemplate.postForObject(url("/api/news/add"), body, Map.class);
    }

    public FetchResult<Void> addNewsWithAlert(String id,
                                              String text,
                                              List<Float> vector,
                                              Map<String, Object> metadata,
                                              String alertSource,
                                              String alertCode,
                                              String alertSubject) {
        addNews(id, text, vector, metadata);
        return FetchResult.of((Void) null);
    }

    /** Query similar news vectors from ChromaDB. */
    public List<Map<String, Object>> querySimilar(List<Float> vector, int days, double threshold) {
        var body = Map.of("vector", vector, "days", days, "threshold", threshold);
        var resp = restTemplate.postForObject(url("/api/news/similar"), body, Map.class);
        @SuppressWarnings("unchecked")
        var matches = (List<Map<String, Object>>) resp.get("matches");
        return matches;
    }

    public FetchResult<List<Map<String, Object>>> querySimilarWithAlert(List<Float> vector,
                                                                        int days,
                                                                        double threshold,
                                                                        String alertSource,
                                                                        String alertCode,
                                                                        String alertSubject) {
        return FetchResult.of(querySimilar(vector, days, threshold));
    }

    /** Store an entity vector in ChromaDB. */
    public void addEntity(String id, String name, String type, List<Float> vector, Map<String, Object> metadata) {
        var body = Map.of("id", id, "name", name, "type", type, "vector", vector, "metadata", metadata);
        restTemplate.postForObject(url("/api/entity/add"), body, Map.class);
    }

    public FetchResult<Void> addEntityWithAlert(String id,
                                                String name,
                                                String type,
                                                List<Float> vector,
                                                Map<String, Object> metadata,
                                                String alertSource,
                                                String alertCode,
                                                String alertSubject) {
        addEntity(id, name, type, vector, metadata);
        return FetchResult.of((Void) null);
    }

    /** Query historical entity vectors from ChromaDB. */
    public List<Map<String, Object>> queryEntityHistory(List<Float> vector, double threshold) {
        var body = Map.of("vector", vector, "threshold", threshold);
        var resp = restTemplate.postForObject(url("/api/entity/history"), body, Map.class);
        @SuppressWarnings("unchecked")
        var matches = (List<Map<String, Object>>) resp.get("matches");
        return matches;
    }

    public FetchResult<List<Map<String, Object>>> queryEntityHistoryWithAlert(List<Float> vector,
                                                                              double threshold,
                                                                              String alertSource,
                                                                              String alertCode,
                                                                              String alertSubject) {
        return FetchResult.of(queryEntityHistory(vector, threshold));
    }

    public FetchResult<List<List<Float>>> embedWithAlert(List<String> texts,
                                                         String alertSource,
                                                         String alertCode,
                                                         String alertSubject) {
        return FetchResult.of(embed(texts));
    }

    /** Classify user intent using ml-server embedding similarity. */
    public IntentClassification classifyIntent(String text) {
        var body = Map.of("text", text != null ? text : "");
        var resp = restTemplate.postForObject(url("/api/intent/classify"), body, Map.class);
        if (resp == null) {
            return new IntentClassification("default", 0.0);
        }
        return new IntentClassification(
                (String) resp.getOrDefault("intent", "default"),
                readDouble(resp.get("confidence"))
        );
    }

    private int readInt(Object value) {
        return value instanceof Number number ? number.intValue() : -1;
    }

    private double readDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0d;
    }

    public record SemanticRankMatch(int index, String candidate, double score, double embedScore, double rerankScore) {
    }

    public record IntentClassification(String intent, double confidence) {
    }
}
