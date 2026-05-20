package com.bot.service;

import com.bot.config.AppConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeChatPusher {

    private final RestTemplate restTemplate;
    private final AppConfig.BotConfig config;

    private String cachedQqToken;
    private long qqTokenExpires;

    private String cachedWxToken;
    private long wxTokenExpires;

    private static final String QQ_AUTH_URL = "https://bots.qq.com/app/getAppAccessToken";
    private static final String QQ_API_BASE = "https://api.sgroup.qq.com";
    private static final String WX_TOKEN_URL = "https://qyapi.weixin.qq.com/cgi-bin/gettoken";
    private static final String WX_SEND_URL = "https://qyapi.weixin.qq.com/cgi-bin/message/send";

    public void push(String content) {
        // 1. QQ Bot 直连优先
        var qq = config.getQq();
        if (qq != null && qq.getAppId() != null && !qq.getAppId().isEmpty()) {
            pushViaQQ(content, qq);
            return;
        }

        // 2. 企业微信自建应用
        var wc = config.getWechat();
        if (wc.getCorpId() != null && !wc.getCorpId().isEmpty()) {
            pushViaWxCorp(content, wc);
            return;
        }

        // 3. Webhook 兜底
        String url = wc.getWebhookUrl();
        if (url != null && !url.isEmpty()) {
            pushViaWebhook(content, url);
            return;
        }

        log.info("=== Push (no config) ===\n{}", content);
    }

    public boolean replyToQqUser(String openId, String content, String msgId) {
        return replyViaQQ("/v2/users/" + openId + "/messages", content, msgId);
    }

    public boolean replyToQqGroup(String groupOpenId, String content, String msgId) {
        return replyViaQQ("/v2/groups/" + groupOpenId + "/messages", content, msgId);
    }

    // ==================== QQ Bot 直连 ====================

    private void pushViaQQ(String content, AppConfig.BotConfig.QqConfig qq) {
        try {
            String type = qq.getTargetType() != null ? qq.getTargetType() : "group";
            String path = type.equals("user")
                    ? "/v2/users/" + qq.getTarget() + "/messages"
                    : "/v2/groups/" + qq.getTarget() + "/messages";

            sendQqMessage(path, buildQqTextBody(content, null), qq);
            log.info("Push sent via QQ Bot → {}", type);
        } catch (Exception e) {
            log.error("QQ push failed: {}", e.getMessage());
            log.info("=== Fallback ===\n{}", content);
        }
    }

    private boolean replyViaQQ(String path, String content, String msgId) {
        var qq = config.getQq();
        if (qq == null || qq.getAppId() == null || qq.getAppId().isBlank()) {
            log.warn("QQ reply skipped: QQ app config missing");
            return false;
        }
        try {
            sendQqMessage(path, buildQqTextBody(content, msgId), qq);
            return true;
        } catch (Exception e) {
            log.error("QQ reply failed: {}", e.getMessage());
            return false;
        }
    }

    private Map<String, Object> buildQqTextBody(String content, String msgId) {
        var body = new HashMap<String, Object>();
        body.put("content", content);
        body.put("msg_type", 0);
        if (msgId != null && !msgId.isBlank()) {
            body.put("msg_id", msgId);
            body.put("msg_seq", 1);
        }
        return body;
    }

    private void sendQqMessage(String path, Map<String, Object> body, AppConfig.BotConfig.QqConfig qq) {
        String token = getQqAccessToken(qq);
        postJson(QQ_API_BASE + path, body, token);
    }

    private String getQqAccessToken(AppConfig.BotConfig.QqConfig qq) {
        long now = System.currentTimeMillis() / 1000;
        if (cachedQqToken != null && now < qqTokenExpires - 60) {
            return cachedQqToken;
        }
        var body = Map.of("appId", qq.getAppId(), "clientSecret", qq.getAppSecret());
        var resp = restTemplate.exchange(QQ_AUTH_URL, HttpMethod.POST,
                jsonEntity(body, null),
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {}).getBody();
        cachedQqToken = (String) resp.get("access_token");
        Object exp = resp.getOrDefault("expires_in", "7200");
        qqTokenExpires = now + (exp instanceof Number n ? n.intValue() : 7200);
        return cachedQqToken;
    }

    // ==================== 企业微信自建应用 ====================

    private void pushViaWxCorp(String content, AppConfig.BotConfig.WechatConfig wc) {
        try {
            String token = getWxAccessToken(wc);
            var body = Map.of(
                    "touser", wc.getToUser() != null ? wc.getToUser() : "@all",
                    "msgtype", "text",
                    "agentid", Integer.parseInt(wc.getAgentId()),
                    "text", Map.of("content", content)
            );
            postJson(WX_SEND_URL + "?access_token=" + token, body, null);
            log.info("Push sent via 企业微信");
        } catch (Exception e) {
            log.error("WxCorp push failed: {}", e.getMessage());
            log.info("=== Fallback ===\n{}", content);
        }
    }

    private String getWxAccessToken(AppConfig.BotConfig.WechatConfig wc) {
        long now = System.currentTimeMillis() / 1000;
        if (cachedWxToken != null && now < wxTokenExpires - 60) {
            return cachedWxToken;
        }
        String url = WX_TOKEN_URL + "?corpid=" + wc.getCorpId() + "&corpsecret=" + wc.getSecret();
        var resp = restTemplate.exchange(url, HttpMethod.GET, null,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {}).getBody();
        cachedWxToken = (String) resp.get("access_token");
        Object exp = resp.getOrDefault("expires_in", 7200);
        wxTokenExpires = now + (exp instanceof Number n ? n.intValue() : 7200);
        return cachedWxToken;
    }

    // ==================== Webhook ====================

    private void pushViaWebhook(String content, String url) {
        try {
            Object body = buildWebhookBody(content, url);
            postJson(url, body, null);
            log.info("Push sent via webhook ({} type)", detectType(url));
        } catch (Exception e) {
            log.error("Webhook push failed: {}", e.getMessage());
            log.info("=== Fallback ===\n{}", content);
        }
    }

    private Object buildWebhookBody(String content, String url) {
        return switch (detectType(url)) {
            case QYWX -> Map.of("msgtype", "text", "text", Map.of("content", content));
            case PUSHPLUS -> Map.of(
                    "token", extractToken(url, "/send/"),
                    "title", "今日热点推送",
                    "content", content.replace("\n", "<br>"));
            case FTQQ -> Map.of("title", "今日热点推送", "desp", content.replace("\n", "\n\n"));
            default -> Map.of("msgType", "text", "content", content);
        };
    }

    private enum Channel { QYWX, PUSHPLUS, FTQQ, OPENCLAW }

    private Channel detectType(String url) {
        if (url.contains("qyapi.weixin.qq.com")) return Channel.QYWX;
        if (url.contains("pushplus.plus"))       return Channel.PUSHPLUS;
        if (url.contains("sctapi.ftqq.com"))     return Channel.FTQQ;
        return Channel.OPENCLAW;
    }

    private String extractToken(String url, String marker) {
        int i = url.indexOf(marker);
        return i >= 0 ? url.substring(i + marker.length()) : "";
    }

    // ==================== 通用 ====================

    private HttpEntity<Map<String, Object>> jsonEntity(Object body, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        @SuppressWarnings("unchecked")
        var b = (Map<String, Object>) body;
        return new HttpEntity<>(b, headers);
    }

    private void postJson(String url, Object body, String bearerToken) {
        restTemplate.postForObject(url, jsonEntity(body, bearerToken), String.class);
    }
}
