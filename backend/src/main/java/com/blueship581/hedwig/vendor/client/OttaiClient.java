package com.blueship581.hedwig.vendor.client;

import com.blueship581.hedwig.domain.enums.TrendDirection;
import com.blueship581.hedwig.exception.VendorException;
import com.blueship581.hedwig.vendor.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Client for the Ottai CGM vendor API.
 *
 * Base URL: https://api.ottai.com
 * Auth: Bearer token (JWT HS512, valid 180 days)
 * Token validation: parse JWT payload Base64, extract "exp" field
 */
@Slf4j
@Component
public class OttaiClient implements VendorClient {

    private static final String BASE_URL = "https://api.ottai.com";

    // arrowType integer → TrendDirection mapping per API spec
    private static final Map<Integer, TrendDirection> ARROW_MAP = Map.of(
            1, TrendDirection.DOUBLE_UP,
            2, TrendDirection.SINGLE_UP,
            3, TrendDirection.FLAT,
            4, TrendDirection.SINGLE_DOWN,
            5, TrendDirection.DOUBLE_DOWN,
            6, TrendDirection.FORTY_FIVE_UP,
            7, TrendDirection.FORTY_FIVE_DOWN
    );

    private final WebClient webClient;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public OttaiClient(WebClient.Builder webClientBuilder,
                       com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public VendorTokenInfo validateToken(String accessToken) {
        String normalizedToken = VendorTokenNormalizer.normalize(accessToken);
        log.info("[Ottai] validateToken called, token length={}", normalizedToken != null ? normalizedToken.length() : 0);
        try {
            // Decode JWT payload from Base64 without verifying signature
            String[] parts = normalizedToken.split("\\.");
            if (parts.length < 2) {
                log.warn("[Ottai] Invalid JWT format: expected 3 parts, got {}", parts.length);
                return VendorTokenInfo.builder().valid(false).build();
            }
            String payloadJson = new String(
                    Base64.getUrlDecoder().decode(padBase64(parts[1])),
                    StandardCharsets.UTF_8
            );
            log.info("[Ottai] JWT payload: {}", payloadJson);
            JsonNode payload = objectMapper.readTree(payloadJson);
            long expEpochSec = payload.get("exp").asLong();
            String userId = payload.has("userId") ? payload.get("userId").asText() : null;
            Instant expiresAt = Instant.ofEpochSecond(expEpochSec);
            boolean valid = Instant.now().isBefore(expiresAt);

            log.info("[Ottai] Token validation: userId={}, expiresAt={}, valid={}", userId, expiresAt, valid);

            return VendorTokenInfo.builder()
                    .token(normalizedToken)
                    .userId(userId)
                    .expiresAt(expiresAt)
                    .valid(valid)
                    .build();
        } catch (Exception e) {
            log.error("[Ottai] Failed to parse JWT token", e);
            return VendorTokenInfo.builder().valid(false).build();
        }
    }

    /**
     * Ottai login requires WeChat mini-program codes and cannot be automated server-side.
     * This method is provided for interface compliance only.
     */
    @Override
    public VendorTokenInfo login(VendorLoginRequest loginRequest) {
        throw VendorException.unsupportedPasswordLogin("欧泰");
    }

    @Override
    public List<VendorSubject> getMonitoredSubjects(String accessToken, String vendorUserId) {
        log.info("[Ottai] getMonitoredSubjects: vendorUserId={}", vendorUserId);
        try {
            log.info("[Ottai] POST {}/link/application/server/invite/link/relatives", BASE_URL);
            JsonNode response = webClient.post()
                    .uri("/link/application/server/invite/link/relatives")
                    .headers(h -> buildHeaders(h, accessToken, vendorUserId))
                    .bodyValue(Map.of("unit", "mmol_L"))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            log.info("[Ottai] getMonitoredSubjects response: {}", response);
            validateResponse(response, "getMonitoredSubjects");

            List<VendorSubject> subjects = new ArrayList<>();
            for (JsonNode item : response.get("data")) {
                String subjectUserId = item.has("fromUserId")
                        ? String.valueOf(item.get("fromUserId").asLong()) : null;
                String deviceId = item.has("fromUserDeviceId")
                        ? String.valueOf(item.get("fromUserDeviceId").asLong()) : null;
                String displayName = item.has("fromUserRemark")
                        ? item.get("fromUserRemark").asText() : null;

                Double latestGlucose = null;
                if (item.has("glucose") && !item.get("glucose").isNull()) {
                    try {
                        latestGlucose = Double.parseDouble(item.get("glucose").asText());
                    } catch (NumberFormatException ignored) {
                    }
                }

                Long sensorRestSeconds = null;
                if (item.has("restDeviceTime") && !item.get("restDeviceTime").isNull()) {
                    sensorRestSeconds = item.get("restDeviceTime").asLong();
                }

                subjects.add(VendorSubject.builder()
                        .subjectId(subjectUserId)
                        .deviceId(deviceId)
                        .displayName(displayName)
                        .latestGlucoseMmol(latestGlucose)
                        .sensorRestSeconds(sensorRestSeconds)
                        .build());
            }
            return subjects;

        } catch (VendorException e) {
            throw e;
        } catch (WebClientResponseException e) {
            throw apiError("获取监测对象列表", e);
        } catch (Exception e) {
            throw VendorException.serviceUnavailable("欧泰", "获取监测对象列表", e.getMessage(), e);
        }
    }

    /**
     * Lightweight realtime fetch: one POST to /relatives returns latest glucose,
     * receiveTime, and arrowType for ALL monitored subjects.
     *
     * @return map of vendorSubjectId (fromUserId) -> VendorGlucoseData
     */
    public Map<String, VendorGlucoseData> getRealtimeFromRelatives(
            String accessToken, String vendorUserId) {
        log.debug("[Ottai] getRealtimeFromRelatives: vendorUserId={}", vendorUserId);
        try {
            JsonNode response = webClient.post()
                    .uri("/link/application/server/invite/link/relatives")
                    .headers(h -> buildHeaders(h, accessToken, vendorUserId))
                    .bodyValue(Map.of("unit", "mmol_L"))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            validateResponse(response, "getRealtimeFromRelatives");

            Map<String, VendorGlucoseData> result = new HashMap<>();
            for (JsonNode item : response.get("data")) {
                String subjectId = String.valueOf(item.get("fromUserId").asLong());

                if (!item.has("glucose") || item.get("glucose").isNull()
                        || !item.has("receiveTime") || item.get("receiveTime").isNull()) {
                    continue;
                }

                double glucose;
                try {
                    glucose = Double.parseDouble(item.get("glucose").asText());
                } catch (NumberFormatException e) {
                    log.warn("[Ottai] Skipping subject {} with unparseable glucose: {}",
                            subjectId, item.get("glucose").asText());
                    continue;
                }

                long receiveTimeMs = item.get("receiveTime").asLong();
                int arrowType = item.has("arrowType") ? item.get("arrowType").asInt() : 0;

                result.put(subjectId, VendorGlucoseData.builder()
                        .glucoseMmol(glucose)
                        .readingTime(Instant.ofEpochMilli(receiveTimeMs))
                        .trendDirection(mapArrowType(arrowType))
                        .build());
            }
            log.debug("[Ottai] getRealtimeFromRelatives returned {} subjects", result.size());
            return result;

        } catch (VendorException e) {
            throw e;
        } catch (WebClientResponseException e) {
            throw apiError("获取实时血糖数据", e);
        } catch (Exception e) {
            throw VendorException.serviceUnavailable("欧泰", "获取实时血糖数据", e.getMessage(), e);
        }
    }

    @Override
    public VendorGlucoseData getLatestGlucose(String accessToken, String vendorUserId, VendorSubject subject) {
        // The relatives list already contains the latest glucose; use historical endpoint for accuracy
        List<VendorGlucoseData> history = getHistoricalGlucose(accessToken, vendorUserId, subject);
        if (history.isEmpty()) {
            throw VendorException.noGlucoseData("欧泰", subject.getSubjectId());
        }
        return history.get(history.size() - 1);
    }

    @Override
    public List<VendorGlucoseData> getHistoricalGlucose(String accessToken, String vendorUserId, VendorSubject subject) {
        log.info("[Ottai] getHistoricalGlucose: vendorUserId={}, subjectId={}, deviceId={}",
                vendorUserId, subject.getSubjectId(), subject.getDeviceId());
        try {
            log.info("[Ottai] GET {}/link/application/server/tag/search/queryMonitorBase?deviceId={}&userId={}&isOpen=1&timeType=2&unit=mmol_L",
                    BASE_URL, subject.getDeviceId(), subject.getSubjectId());
            JsonNode response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/link/application/server/tag/search/queryMonitorBase")
                            .queryParam("deviceId", subject.getDeviceId())
                            .queryParam("userId", subject.getSubjectId())
                            .queryParam("isOpen", "1")
                            .queryParam("timeType", "2")   // last 24 hours
                            .queryParam("unit", "mmol_L")
                            .build())
                    .headers(h -> buildHeaders(h, accessToken, vendorUserId))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            log.info("[Ottai] getHistoricalGlucose response code: {}",
                    response != null ? response.path("code").asText() : "null");
            validateResponse(response, "getHistoricalGlucose");

            JsonNode data = response.get("data");
            JsonNode curveList = data.get("curveList");
            if (curveList == null || curveList.isNull() || !curveList.isArray()) {
                return Collections.emptyList();
            }

            List<VendorGlucoseData> readings = new ArrayList<>();
            for (JsonNode point : curveList) {
                double glucoseMmol = point.get("glucose").asDouble();
                long monitorTimeMs = point.get("monitorTime").asLong();
                readings.add(VendorGlucoseData.builder()
                        .glucoseMmol(glucoseMmol)
                        .readingTime(Instant.ofEpochMilli(monitorTimeMs))
                        .trendDirection(TrendDirection.NONE)  // historical data has no trend
                        .build());
            }
            return readings;

        } catch (VendorException e) {
            throw e;
        } catch (WebClientResponseException e) {
            throw apiError("获取历史血糖数据", e);
        } catch (Exception e) {
            throw VendorException.serviceUnavailable("欧泰", "获取历史血糖数据", e.getMessage(), e);
        }
    }

    private void buildHeaders(org.springframework.http.HttpHeaders headers,
                              String accessToken, String vendorUserId) {
        String normalizedToken = VendorTokenNormalizer.normalize(accessToken);
        headers.set("Host", "api.ottai.com");
        headers.set("Content-Type", "application/json");
        headers.setBearerAuth(normalizedToken);
        headers.set("userId", vendorUserId);
        headers.set("unit", "mmol_L");
        headers.set("timeStamp", String.valueOf(System.currentTimeMillis()));
        headers.set("timezone", "28800");
        headers.set("appName", "miniprogram");
    }

    private TrendDirection mapArrowType(int arrowType) {
        return ARROW_MAP.getOrDefault(arrowType, TrendDirection.NONE);
    }

    private void validateResponse(JsonNode response, String operation) {
        if (response == null) {
            throw VendorException.responseInvalid("欧泰", localizeOperation(operation), "接口返回为空");
        }
        JsonNode codeNode = response.get("code");
        if (codeNode == null || !"OK".equals(codeNode.asText())) {
            String msg = response.has("msg") ? response.get("msg").asText() : "未知错误";
            throw VendorException.responseInvalid("欧泰", localizeOperation(operation), msg);
        }
    }

    private VendorException apiError(String operation, WebClientResponseException e) {
        String detail = extractErrorDetail(e.getResponseBodyAsString());
        String errorDetail = (detail == null || detail.isBlank())
                ? "HTTP " + e.getStatusCode().value()
                : detail;
        return VendorException.serviceUnavailable("欧泰", operation, errorDetail, e);
    }

    private String extractErrorDetail(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.hasNonNull("msg")) {
                return root.get("msg").asText();
            }
            if (root.hasNonNull("message")) {
                return root.get("message").asText();
            }
            if (root.hasNonNull("error")) {
                return root.get("error").asText();
            }
        } catch (Exception ignored) {
        }
        return body.length() > 160 ? body.substring(0, 160) + "..." : body;
    }

    private String localizeOperation(String operation) {
        return switch (operation) {
            case "getMonitoredSubjects" -> "获取监测对象列表";
            case "getRealtimeFromRelatives" -> "获取实时血糖数据";
            case "getHistoricalGlucose" -> "获取历史血糖数据";
            default -> operation;
        };
    }

    private static String padBase64(String base64) {
        int padding = (4 - base64.length() % 4) % 4;
        return base64 + "=".repeat(padding);
    }
}
