package com.bot.client;

import com.bot.config.AppConfig;
import com.bot.model.FetchResult;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResilientPythonMLClientTest {

        @Test
        void chatShortCircuitsAfterCircuitBreakerOpens() {
                RestTemplate restTemplate = mock(RestTemplate.class);
                ResilientPythonMLClient client = client(restTemplate);
                when(restTemplate.postForObject(contains("/api/chat"), any(), eq(Map.class)))
                                .thenThrow(new ResourceAccessException("timeout"));

                for (int index = 0; index < 5; index++) {
                        assertEquals("当前 AI 服务暂时不可用，请稍后再试。", client.chat("你好", List.of(), null));
                }

                verify(restTemplate, times(4)).postForObject(contains("/api/chat"), any(), eq(Map.class));
        }

    @Test
    void chatReturnsExplicitFallbackWhenEndpointFails() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ResilientPythonMLClient client = client(restTemplate);
        when(restTemplate.postForObject(contains("/api/chat"), any(), eq(Map.class)))
                .thenThrow(new ResourceAccessException("timeout"));

        String reply = client.chat("你好", List.of(), null);

        assertEquals("当前 AI 服务暂时不可用，请稍后再试。", reply);
        verify(restTemplate, times(1)).postForObject(contains("/api/chat"), any(), eq(Map.class));
    }

        @Test
        void chatUsesConfiguredFallbackReply() {
                RestTemplate restTemplate = mock(RestTemplate.class);
                ResilientPythonMLClient client = client(restTemplate, config ->
                                config.getMl().getFallback().setChatReply("AI 忙线中，请晚点再问"));
                when(restTemplate.postForObject(contains("/api/chat"), any(), eq(Map.class)))
                                .thenThrow(new ResourceAccessException("timeout"));

                String reply = client.chat("你好", List.of(), null);

                assertEquals("AI 忙线中，请晚点再问", reply);
        }

    @Test
    void translateRetriesConnectionFailureThenReturnsBlankTranslations() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ResilientPythonMLClient client = client(restTemplate);
        when(restTemplate.postForObject(contains("/api/translate"), any(), eq(Map.class)))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenThrow(new ResourceAccessException("timeout again"));

        List<String> translated = client.translateTexts(List.of("A", "B"), "title");

        assertEquals(List.of("", ""), translated);
        verify(restTemplate, times(2)).postForObject(contains("/api/translate"), any(), eq(Map.class));
    }

        @Test
        void translateUsesConfiguredRetryAttempts() {
                RestTemplate restTemplate = mock(RestTemplate.class);
                ResilientPythonMLClient client = client(restTemplate, config ->
                                config.getMl().getRetry().setConnectionFailureMaxAttempts(3));
                when(restTemplate.postForObject(contains("/api/translate"), any(), eq(Map.class)))
                                .thenThrow(new ResourceAccessException("timeout-1"))
                                .thenThrow(new ResourceAccessException("timeout-2"))
                                .thenThrow(new ResourceAccessException("timeout-3"));

                List<String> translated = client.translateTexts(List.of("A", "B"), "title");

                assertEquals(List.of("", ""), translated);
                verify(restTemplate, times(3)).postForObject(contains("/api/translate"), any(), eq(Map.class));
        }

    @Test
    void rankCandidatesReturnsEmptyListWhenEndpointFails() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ResilientPythonMLClient client = client(restTemplate);
        when(restTemplate.postForObject(contains("/api/semantic/rank"), any(), eq(Map.class)))
                .thenThrow(new IllegalStateException("boom"));

        List<PythonMLClient.SemanticRankMatch> matches = client.rankCandidates("waymo", List.of("candidate"), 1);

        assertTrue(matches.isEmpty());
        verify(restTemplate, times(1)).postForObject(contains("/api/semantic/rank"), any(), eq(Map.class));
    }

    @Test
    void commentarySentimentAndNerFallbackToEmptyPayloads() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ResilientPythonMLClient client = client(restTemplate);
        when(restTemplate.postForObject(contains("/api/commentary"), any(), eq(Map.class)))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenThrow(new ResourceAccessException("timeout again"));
        when(restTemplate.postForObject(contains("/api/sentiment"), any(), eq(Map.class)))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenThrow(new ResourceAccessException("timeout again"));
        when(restTemplate.postForObject(contains("/api/ner"), any(), eq(Map.class)))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenThrow(new ResourceAccessException("timeout again"));

        assertEquals("", client.commentary("内容"));
        assertTrue(client.sentiment(List.of("评论")).isEmpty());
        assertTrue(client.ner("文本").isEmpty());
    }

    @Test
    void embedAndVectorWritesDoNotBreakCallerWhenMlServerFails() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ResilientPythonMLClient client = client(restTemplate);
        when(restTemplate.postForObject(contains("/api/embed"), any(), eq(Map.class)))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenThrow(new ResourceAccessException("timeout again"));
        doThrow(new IllegalStateException("store failed"))
                .when(restTemplate).postForObject(contains("/api/news/add"), any(), eq(Map.class));

        assertTrue(client.embed(List.of("标题")).isEmpty());
        assertDoesNotThrow(() -> client.addNews("n1", "标题", List.of(0.1f, 0.2f), Map.of("date", "2026-05-23")));
    }

    @Test
    void querySimilarWithAlertReturnsSystemAlertOnFallback() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ResilientPythonMLClient client = client(restTemplate);
        when(restTemplate.postForObject(contains("/api/news/similar"), any(), eq(Map.class)))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenThrow(new ResourceAccessException("timeout again"));

        FetchResult<List<Map<String, Object>>> result = client.querySimilarWithAlert(
                List.of(0.1f, 0.2f),
                7,
                0.8d,
                "TRACKING",
                "NEWS_SIMILAR_FAILED",
                "测试新闻");

        assertTrue(result.data().isEmpty());
        assertEquals(1, result.alerts().size());
        assertEquals("NEWS_SIMILAR_FAILED", result.alerts().get(0).code());
        assertTrue(result.alerts().get(0).message().contains("测试新闻 -> news-similar/ResourceAccessException"));
    }

    @Test
    void addNewsWithAlertReturnsSystemAlertOnFallback() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ResilientPythonMLClient client = client(restTemplate);
        doThrow(new IllegalStateException("store failed"))
                .when(restTemplate).postForObject(contains("/api/news/add"), any(), eq(Map.class));

        FetchResult<Void> result = client.addNewsWithAlert(
                "n1",
                "标题",
                List.of(0.1f, 0.2f),
                Map.of("date", "2026-05-23"),
                "TRACKING",
                "NEWS_VECTOR_WRITE_FAILED",
                "测试新闻");

        assertEquals(1, result.alerts().size());
        assertEquals("NEWS_VECTOR_WRITE_FAILED", result.alerts().get(0).code());
        assertTrue(result.alerts().get(0).message().contains("测试新闻 -> news-add/IllegalStateException"));
    }

        @Test
        void withAlertMethodsReturnSystemAlertOnFallback() {
                RestTemplate restTemplate = mock(RestTemplate.class);
                ResilientPythonMLClient client = client(restTemplate);
                when(restTemplate.postForObject(contains("/api/commentary"), any(), eq(Map.class)))
                                .thenThrow(new ResourceAccessException("timeout"))
                                .thenThrow(new ResourceAccessException("timeout again"));

                FetchResult<String> result = client.commentaryWithAlert("内容", "AI点评", "AI_COMMENTARY_FAILED", "测试新闻");

                assertEquals("", result.data());
                assertEquals(1, result.alerts().size());
                assertEquals("AI_COMMENTARY_FAILED", result.alerts().get(0).code());
                assertTrue(result.alerts().get(0).message().contains("测试新闻 -> commentary/ResourceAccessException"));
        }

    private ResilientPythonMLClient client(RestTemplate restTemplate) {
                return client(restTemplate, config -> {
                });
        }

        private ResilientPythonMLClient client(RestTemplate restTemplate,
                                                                                   java.util.function.Consumer<AppConfig.BotConfig> customizer) {
        AppConfig.BotConfig config = new AppConfig.BotConfig();
                config.getMl().setServerUrl("http://ml-server");
                customizer.accept(config);
        return new ResilientPythonMLClient(restTemplate, config);
    }
}