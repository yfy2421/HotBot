package com.bot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AppConfig {

    @Bean
    public Executor taskExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("bot-");
        executor.initialize();
        return executor;
    }

    @Bean
    public RestTemplate restTemplate() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
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
        private WeatherConfig weather;
        private QqConfig qq;
        private WechatConfig wechat;
        private NewsConfig news;

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
            private String hackernewsRss;
            private String juejinApi;
            private String cardOutputDir;
        }
    }
}
