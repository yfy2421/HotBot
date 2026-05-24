package com.bot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AppConfig {

    @Bean
    public Executor taskExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("bot-");
        executor.initialize();
        return executor;
    }

    @Bean
    public RestTemplate restTemplate(BotConfig botConfig) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(positiveOrDefault(botConfig.getMl().getHttp().getConnectTimeoutMs(), 10_000));
        factory.setReadTimeout(positiveOrDefault(botConfig.getMl().getHttp().getReadTimeoutMs(), 30_000));
        return new RestTemplate(factory);
    }

    @Bean
    @ConfigurationProperties(prefix = "bot")
    public BotConfig botConfig() {
        return new BotConfig();
    }

    @Data
    public static class BotConfig {
        private String mlServerUrl;
        private MlConfig ml = new MlConfig();
        private AssistantConfig assistant;
        private WeatherConfig weather;
        private QqConfig qq;
        private WechatConfig wechat;
        private NewsConfig news;

        public MlConfig getMl() {
            if (ml == null) {
                ml = new MlConfig();
            }
            return ml;
        }

        public String getMlServerUrl() {
            String serverUrl = getMl().getServerUrl();
            if (serverUrl != null && !serverUrl.isBlank()) {
                return serverUrl;
            }
            return mlServerUrl;
        }

        @Data
        public static class MlConfig {
            private String serverUrl;
            private HttpConfig http = new HttpConfig();
            private RetryConfig retry = new RetryConfig();
            private FallbackConfig fallback = new FallbackConfig();

            public HttpConfig getHttp() {
                if (http == null) {
                    http = new HttpConfig();
                }
                return http;
            }

            public RetryConfig getRetry() {
                if (retry == null) {
                    retry = new RetryConfig();
                }
                return retry;
            }

            public FallbackConfig getFallback() {
                if (fallback == null) {
                    fallback = new FallbackConfig();
                }
                return fallback;
            }

            @Data
            public static class HttpConfig {
                private int connectTimeoutMs = 10_000;
                private int readTimeoutMs = 30_000;
            }

            @Data
            public static class RetryConfig {
                private int connectionFailureMaxAttempts = 2;
            }

            @Data
            public static class FallbackConfig {
                private String chatReply = "当前 AI 服务暂时不可用，请稍后再试。";
            }
        }

        @Data
        public static class AssistantConfig {
            private String stateDir;
        }

        @Data
        public static class WeatherConfig {
            private String apiHost;
            private String projectId;
            private String credentialId;
            private String privateKey;
            private String cityId;
            private String airCoordinates;
        }

        @Data
        public static class QqConfig {
            private String appId;        // QQ Bot AppID
            private String appSecret;    // QQ Bot AppSecret
            private String target;       // 目标群 openid 或用户 openid
            private String targetType;   // "group"（发群）或 "user"（发个人），默认 group
        }

        @Data
        public static class WechatConfig {
            private String webhookUrl;
            private String corpId;
            private String agentId;
            private String secret;
            private String toUser;
        }

        @Data
        public static class NewsConfig {
            private List<FeedConfig> rssFeeds;
            private String cardOutputDir;

            @Data
            public static class FeedConfig {
                private String name;
                private String url;
                private String type;      // rss | hn | zhihu
                private String category;  // tech | general
                private String trust;     // official_rss | aggregated | community
                private String fallback;  // optional fallback URL (HN only)
            }
        }
    }

    private int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
