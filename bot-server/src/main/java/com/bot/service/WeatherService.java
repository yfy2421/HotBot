package com.bot.service;

import com.bot.config.AppConfig;
import com.bot.model.FetchResult;
import com.bot.model.SystemAlert;
import com.bot.model.WeatherInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static com.bot.util.TextUtils.hasText;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherService {

    private static final String DEFAULT_CITY_NAME = "广州";
    private static final String WEATHER_PATH = "/v7/weather/3d";
    private static final String AIR_CURRENT_PREFIX = "/airquality/v1/current";

    private final RestTemplate restTemplate;
    private final AppConfig.BotConfig config;
    private final ObjectMapper objectMapper;

    private static final long JWT_TTL_SECONDS = 3600;

    private String cachedJwt;
    private long jwtExpiresAt;

    public boolean isConfigured() {
        return missingWeatherConfigFields().isEmpty();
    }

    public String getConfiguredCityName() {
        return DEFAULT_CITY_NAME;
    }

    public WeatherInfo fetchToday() {
        return fetchTodayWithAlert().data();
    }

    public FetchResult<WeatherInfo> fetchTodayWithAlert() {
        if (!isConfigured()) {
            String missingFields = String.join(", ", missingWeatherConfigFields());
            log.warn("HeFeng weather config incomplete, missing: {}, using placeholder weather",
                    missingFields);
            return FetchResult.of(
                    placeholder(),
                    List.of(SystemAlert.warn("WeatherService", "WEATHER_CONFIG_INCOMPLETE",
                            "和风天气配置缺失：" + missingFields + "，已回退占位天气")));
        }

        try {
            return FetchResult.of(doFetchToday());
        } catch (Exception e) {
            log.error("Failed to fetch weather: {}", e.getMessage());
            return FetchResult.of(
                    placeholder(),
                    List.of(SystemAlert.warn("WeatherService", "WEATHER_FETCH_FAILED",
                            summarizeException(e))));
        }
    }

    public WeatherInfo fetchTodayStrict() {
        if (!isConfigured()) {
            throw new IllegalStateException("HeFeng weather service is not configured, missing: "
                    +
                    String.join(", ", missingWeatherConfigFields()));
        }
        try {
            return doFetchToday();
        } catch (Exception e) {
            throw new IllegalStateException("HeFeng weather request failed: " + e.getMessage(), e);
        }
    }

    private List<String> missingWeatherConfigFields() {
        var wc = config.getWeather();
        if (wc == null) {
            return List.of("bot.weather");
        }

        List<String> missing = new ArrayList<>();
        if (!hasText(wc.getApiHost())) {
            missing.add("HEFENG_API_HOST");
        }
        if (!hasText(wc.getProjectId())) {
            missing.add("HEFENG_PROJECT_ID");
        }
        if (!hasText(wc.getCredentialId())) {
            missing.add("HEFENG_CREDENTIAL_ID");
        }
        if (!hasText(wc.getPrivateKey())) {
            missing.add("HEFENG_PRIVATE_KEY");
        }
        return missing;
    }

    private WeatherInfo doFetchToday() throws Exception {
        var wc = config.getWeather();
        String cityId = wc.getCityId();
        String jwt = getJwt();
        HttpEntity<Void> entity = authHeader(jwt);

        Map<String, Object> weatherResp = fetch(WEATHER_PATH, cityId, entity);
        Map<String, Object> airResp = null;
        try {
            airResp = fetchAirCurrent(entity);
        } catch (Exception e) {
            log.warn("QWeather air quality unavailable for cityId={}: {}", cityId, e.getMessage());
        }

        @SuppressWarnings("unchecked")
        var daily = (List<Map<String, Object>>) weatherResp.get("daily");
        var today = daily.get(0);

        AirQualitySnapshot airQuality = extractAirQuality(airResp);

        return WeatherInfo.builder()
                .city(DEFAULT_CITY_NAME)
                .date(asText(today.get("fxDate")))
                .tempHigh(parseInt(today.get("tempMax"), 0))
                .tempLow(parseInt(today.get("tempMin"), 0))
                .condition(asText(today.get("textDay")))
                .icon(asText(today.get("iconDay")))
                .aqi(airQuality.aqi())
                .aqiLevel(airQuality.level())
                .windDirection(asText(today.get("windDirDay")))
                .windScale(asText(today.get("windScaleDay")))
                .build();
    }

    private Map<String, Object> fetchAirCurrent(HttpEntity<Void> entity) {
        String coordinates = config.getWeather().getAirCoordinates();
        String[] latLon = parseAirCoordinates(coordinates);
        String url = UriComponentsBuilder.fromHttpUrl(resolveApiBaseUrl())
                .path(AIR_CURRENT_PREFIX)
                .pathSegment(latLon[0], latLon[1])
                .toUriString();
        try {
            ResponseEntity<byte[]> resp = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);
            return parseJsonBody(resp.getBody(), resp.getHeaders());
        } catch (HttpStatusCodeException e) {
            String body = summarizeErrorBody(decodeBody(e.getResponseBodyAsByteArray(), e.getResponseHeaders()));
            throw new IllegalStateException("QWeather air quality request failed: "
                    + e.getStatusCode().value()
                    + " "
                    + body
                    + " [url="
                    + url
                    + "]", e);
        }
    }

    private String[] parseAirCoordinates(String coordinates) {
        if (!hasText(coordinates)) {
            throw new IllegalStateException("HEFENG_AIR_COORDINATES is blank");
        }
        String[] parts = coordinates.split(",", 2);
        if (parts.length != 2) {
            throw new IllegalStateException("HEFENG_AIR_COORDINATES must be 'latitude,longitude'");
        }
        String latitude = parts[0].trim();
        String longitude = parts[1].trim();
        if (!hasText(latitude) || !hasText(longitude)) {
            throw new IllegalStateException("HEFENG_AIR_COORDINATES must contain both latitude and longitude");
        }
        return new String[] { latitude, longitude };
    }

    private AirQualitySnapshot extractAirQuality(Map<String, Object> airResp) {
        if (airResp == null) {
            return AirQualitySnapshot.missing();
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> indexes = (List<Map<String, Object>>) airResp.get("indexes");
        if (indexes == null || indexes.isEmpty()) {
            return AirQualitySnapshot.missing();
        }

        Map<String, Object> preferred = indexes.stream()
                .filter(index -> "cn-mee".equalsIgnoreCase(asText(index.get("code"))))
                .findFirst()
                .orElse(indexes.get(0));

        String category = asText(preferred.get("category"));
        String level = hasText(category) ? category : "暂缺";
        int aqi = parseInt(preferred.get("aqiDisplay"), parseInt(preferred.get("aqi"), -1));
        return new AirQualitySnapshot(aqi, level);
    }

    private int parseInt(Object value, int defaultValue) {
        String text = asText(value);
        if (text == null || text.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            try {
                return (int) Math.round(Double.parseDouble(text));
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** Generate or reuse a JWT for HeFeng API authentication. */
    private String getJwt() throws Exception {
        long now = Instant.now().getEpochSecond();
        long iat = now - 30;
        long exp = iat + JWT_TTL_SECONDS;
        if (cachedJwt != null && now < jwtExpiresAt - 60) {
            return cachedJwt;
        }

        var wc = config.getWeather();
        String projectId = wc.getProjectId();
        String kid = wc.getCredentialId();
    PrivateKey privateKey = loadPrivateKey(wc.getPrivateKey());

    String headerJson = "{\"alg\":\"EdDSA\",\"kid\":\"" + escapeJson(kid) + "\"}";
    String payloadJson = "{\"sub\":\"" + escapeJson(projectId) + "\",\"iat\":" + iat + ",\"exp\":" + exp + "}";
    String signingInput = encodeBase64Url(headerJson.getBytes(StandardCharsets.UTF_8))
        + "."
        + encodeBase64Url(payloadJson.getBytes(StandardCharsets.UTF_8));

    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(privateKey);
    signer.update(signingInput.getBytes(StandardCharsets.UTF_8));

    cachedJwt = signingInput + "." + encodeBase64Url(signer.sign());
        jwtExpiresAt = exp;
        return cachedJwt;
    }

    private PrivateKey loadPrivateKey(String privateKeyValue) throws Exception {
    String raw = privateKeyValue
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replaceAll("\\s+", "");
    byte[] pkcs8 = Base64.getDecoder().decode(raw);
        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
    return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    private String encodeBase64Url(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String escapeJson(String value) {
    return value.replace("\\", "\\\\")
        .replace("\"", "\\\"");
    }

    private HttpEntity<Void> authHeader(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set(HttpHeaders.ACCEPT_ENCODING, "identity");
        return new HttpEntity<>(headers);
    }

    private Map<String, Object> fetch(String apiPath, String cityId, HttpEntity<Void> entity) {
        String url = UriComponentsBuilder.fromHttpUrl(resolveApiBaseUrl())
                .path(apiPath)
                .queryParam("location", cityId)
                .toUriString();
        try {
            ResponseEntity<byte[]> resp = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);
            return parseJsonBody(resp.getBody(), resp.getHeaders());
        } catch (HttpStatusCodeException e) {
            String body = summarizeErrorBody(decodeBody(e.getResponseBodyAsByteArray(), e.getResponseHeaders()));
            throw new IllegalStateException("QWeather API request failed: "
                    + e.getStatusCode().value()
                    + " "
                    + body
                    + " [url="
                    + url
                    + "]", e);
        }
    }

    private Map<String, Object> parseJsonBody(byte[] responseBody, HttpHeaders headers) {
        String body = decodeBody(responseBody, headers);
        if (body.isBlank()) {
            throw new IllegalStateException("QWeather API returned an empty response body");
        }
        try {
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (IOException e) {
            throw new IllegalStateException("QWeather API returned unreadable JSON: " + summarizeErrorBody(body), e);
        }
    }

    private String decodeBody(byte[] responseBody, HttpHeaders headers) {
        if (responseBody == null || responseBody.length == 0) {
            return "";
        }

        try (InputStream raw = new ByteArrayInputStream(responseBody);
             InputStream decoded = isGzip(headers) ? new GZIPInputStream(raw) : raw) {
            return new String(decoded.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode QWeather response body", e);
        }
    }

    private boolean isGzip(HttpHeaders headers) {
        if (headers == null) {
            return false;
        }
        List<String> values = headers.get(HttpHeaders.CONTENT_ENCODING);
        if (values == null || values.isEmpty()) {
            return false;
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains("gzip"));
    }

    private String resolveApiBaseUrl() {
        String apiHost = config.getWeather().getApiHost().trim();
        String normalized = apiHost.endsWith("/") ? apiHost.substring(0, apiHost.length() - 1) : apiHost;
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            return normalized;
        }
        return "https://" + normalized;
    }

    private String summarizeErrorBody(String body) {
        if (body == null || body.isBlank()) {
            return "(empty response body)";
        }
        return body.replaceAll("\\s+", " ").trim();
    }

    private String summarizeException(Exception e) {
        return e.getClass().getSimpleName() + ": " + summarizeErrorBody(e.getMessage());
    }

    private record AirQualitySnapshot(int aqi, String level) {
        private static AirQualitySnapshot missing() {
            return new AirQualitySnapshot(-1, "暂缺");
        }
    }

    private WeatherInfo placeholder() {
        return WeatherInfo.builder()
                .city(DEFAULT_CITY_NAME).date("今日")
                .tempHigh(28).tempLow(22).condition("晴")
                .aqi(45).aqiLevel("优")
                .windDirection("东南风").windScale("2级")
                .build();
    }
}
