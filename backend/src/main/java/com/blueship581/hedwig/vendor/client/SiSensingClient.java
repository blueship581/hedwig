package com.blueship581.hedwig.vendor.client;

import com.blueship581.hedwig.domain.enums.TrendDirection;
import com.blueship581.hedwig.exception.BadRequestException;
import com.blueship581.hedwig.exception.VendorException;
import com.blueship581.hedwig.vendor.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.util.*;

/**
 * Client for the SiSensing (硅基仿生) CGM vendor API.
 *
 * Base URL: https://api.sisensing.com
 * Auth: Bearer token (UUID format, ~20 min expiry per docs, but validated via /auth/token/info)
 * Login: phone/password via /lite-sense-app/user/password/login
 */
@Slf4j
@Component
public class SiSensingClient implements VendorClient {

    private static final String BASE_URL = "https://api.sisensing.com";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public SiSensingClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public VendorTokenInfo validateToken(String accessToken) {
        String normalizedToken = VendorTokenNormalizer.normalize(accessToken);
        if (normalizedToken == null || normalizedToken.isBlank()) {
            return VendorTokenInfo.builder().valid(false).build();
        }
        try {
            JsonNode response = webClient.get()
                    .uri("/auth/token/info")
                    .headers(h -> buildHeaders(h, normalizedToken))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || isFailureResponse(response)) {
                return VendorTokenInfo.builder().valid(false).build();
            }

            JsonNode tokenInfo = extractResponseData(response);
            if (tokenInfo == null || tokenInfo.isNull()) {
                return VendorTokenInfo.builder().valid(false).build();
            }

            String returnedToken = firstText(tokenInfo, "token", "accessToken");
            if (returnedToken == null || returnedToken.isBlank()) {
                return VendorTokenInfo.builder().valid(false).build();
            }

            String userId = firstText(tokenInfo, "userId", "id");
            Instant expiresAt = null;
            JsonNode expireTime = tokenInfo.get("expireTime");
            if (expireTime != null && !expireTime.isNull()) {
                long expireMs = Long.parseLong(expireTime.asText());
                expiresAt = Instant.ofEpochMilli(expireMs);
            }

            boolean valid = expiresAt == null || Instant.now().isBefore(expiresAt);
            return VendorTokenInfo.builder()
                    .token(returnedToken)
                    .userId(userId)
                    .expiresAt(expiresAt)
                    .valid(valid)
                    .build();

        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 400 || status == 401 || status == 403) {
                log.debug("SiSensing token validation failed with status {}", e.getStatusCode());
                return VendorTokenInfo.builder().valid(false).build();
            }
            throw apiError("校验访问令牌", e);
        } catch (Exception e) {
            throw VendorException.tokenValidationFailed("硅基轻享", e);
        }
    }

    @Override
    public VendorTokenInfo login(VendorLoginRequest loginRequest) {
        String phone = normalizeCredential(loginRequest.getUsername());
        String password = normalizeCredential(loginRequest.getPassword());

        if (phone == null || password == null) {
            throw new BadRequestException("请输入厂商账号和密码");
        }

        try {
            JsonNode response = webClient.post()
                    .uri("/lite-sense-app/user/password/login")
                    .headers(this::buildCommonHeaders)
                    .bodyValue(Map.of(
                            "phone", phone,
                            "password", password
                    ))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null) {
                throw VendorException.responseInvalid("硅基轻享", "完成登录", "登录接口返回为空");
            }

            if (isFailureResponse(response)) {
                String detail = extractErrorDetail(response.toString());
                throw VendorException.loginFailed(
                        "硅基轻享",
                        detail == null || detail.isBlank() ? "账号密码错误" : detail
                );
            }

            String token = extractLoginToken(response);
            if (token == null || token.isBlank()) {
                throw VendorException.responseInvalid("硅基轻享", "完成登录", "未返回访问令牌");
            }

            VendorTokenInfo tokenInfo = validateToken(token);
            if (!tokenInfo.isValid()) {
                throw VendorException.responseInvalid("硅基轻享", "完成登录", "返回的访问令牌校验失败");
            }
            return tokenInfo;

        } catch (VendorException e) {
            throw e;
        } catch (WebClientResponseException e) {
            throw apiError("账号密码登录", e);
        } catch (Exception e) {
            throw new VendorException("硅基轻享账号登录失败，请检查账号和密码后重试", "硅基轻享登录异常", e);
        }
    }

    @Override
    public List<VendorSubject> getMonitoredSubjects(String accessToken, String vendorUserId) {
        try {
            JsonNode response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/lite-sense-app/follow/list")
                            .queryParam("pageNum", "1")
                            .queryParam("pageSize", "9999")
                            .queryParam("status", "3")  // confirmed follow relationships
                            .build())
                    .headers(h -> buildHeaders(h, accessToken))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null) {
                throw VendorException.responseInvalid("硅基轻享", "获取监测对象列表", "接口返回为空");
            }

            if (isFailureResponse(response)) {
                String detail = extractErrorDetail(response.toString());
                throw VendorException.responseInvalid(
                        "硅基轻享",
                        "获取监测对象列表",
                        detail == null || detail.isBlank() ? "接口返回异常" : detail
                );
            }

            JsonNode payload = extractResponseData(response);
            JsonNode records = payload == null ? null : payload.get("records");
            if (records == null || !records.isArray()) {
                return Collections.emptyList();
            }

            List<VendorSubject> subjects = new ArrayList<>();
            for (JsonNode record : records) {
                String followId = record.has("id") ? record.get("id").asText() : null;
                String displayName = null;
                if (record.has("followedUserInfo")) {
                    JsonNode userInfo = record.get("followedUserInfo");
                    displayName = userInfo.has("nickName")
                            ? userInfo.get("nickName").asText()
                            : (userInfo.has("userName") ? userInfo.get("userName").asText() : null);
                }

                Double latestGlucose = null;
                if (record.has("followedDeviceGlucoseDataPO") && !record.get("followedDeviceGlucoseDataPO").isNull()) {
                    JsonNode glucoseData = record.get("followedDeviceGlucoseDataPO");
                    if (glucoseData.has("latestGlucoseValue") && !glucoseData.get("latestGlucoseValue").isNull()) {
                        latestGlucose = glucoseData.get("latestGlucoseValue").asDouble();
                    }
                }

                subjects.add(VendorSubject.builder()
                        .subjectId(followId)   // SiSensing uses followId as the identifier
                        .deviceId(null)        // not needed; glucose fetched by followId
                        .displayName(displayName)
                        .latestGlucoseMmol(latestGlucose)
                        .build());
            }
            return subjects;

        } catch (VendorException e) {
            throw e;
        } catch (WebClientResponseException e) {
            throw apiError("获取监测对象列表", e);
        } catch (Exception e) {
            throw VendorException.serviceUnavailable("硅基轻享", "获取监测对象列表", e.getMessage(), e);
        }
    }

    /**
     * Lightweight realtime fetch: one GET to /follow/list returns latest glucose
     * for ALL monitored subjects via followedDeviceGlucoseDataPO.
     *
     * @return map of followId (subjectId) -> VendorGlucoseData
     */
    public Map<String, VendorGlucoseData> getRealtimeFromFollowList(String accessToken) {
        log.debug("[SiSensing] getRealtimeFromFollowList");
        try {
            JsonNode response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/lite-sense-app/follow/list")
                            .queryParam("pageNum", "1")
                            .queryParam("pageSize", "9999")
                            .queryParam("status", "3")
                            .build())
                    .headers(h -> buildHeaders(h, accessToken))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || isFailureResponse(response)) {
                throw VendorException.responseInvalid("硅基轻享", "获取实时血糖数据", "接口返回异常");
            }

            JsonNode payload = extractResponseData(response);
            JsonNode records = payload == null ? null : payload.get("records");
            if (records == null || !records.isArray()) {
                return Collections.emptyMap();
            }

            Map<String, VendorGlucoseData> result = new HashMap<>();
            for (JsonNode record : records) {
                String followId = record.has("id") ? record.get("id").asText() : null;
                if (followId == null) {
                    continue;
                }

                JsonNode glucoseData = record.get("followedDeviceGlucoseDataPO");
                if (glucoseData == null || glucoseData.isNull()) {
                    continue;
                }
                log.debug("[SiSensing] followId={}, glucoseDataPO={}", followId, glucoseData);

                if (!glucoseData.has("latestGlucoseValue")
                        || glucoseData.get("latestGlucoseValue").isNull()) {
                    continue;
                }

                double glucose = glucoseData.get("latestGlucoseValue").asDouble();

                // Extract timestamp: try common field names
                Instant readingTime = null;
                for (String field : List.of("latestGlucoseTime", "monitorTime", "updateTime", "createTime")) {
                    if (glucoseData.has(field) && !glucoseData.get(field).isNull()) {
                        long ts = glucoseData.get(field).asLong();
                        if (ts > 0) {
                            readingTime = Instant.ofEpochMilli(ts);
                            break;
                        }
                    }
                }
                if (readingTime == null) {
                    continue; // no timestamp = unusable
                }

                // Extract trend if available
                int trendRaw = 0;
                for (String field : List.of("trend", "s", "arrowType")) {
                    if (glucoseData.has(field) && !glucoseData.get(field).isNull()) {
                        trendRaw = glucoseData.get(field).asInt(0);
                        break;
                    }
                }

                result.put(followId, VendorGlucoseData.builder()
                        .glucoseMmol(glucose)
                        .readingTime(readingTime)
                        .trendDirection(mapSiSensingTrend(trendRaw))
                        .build());
            }
            log.debug("[SiSensing] getRealtimeFromFollowList returned {} subjects", result.size());
            return result;

        } catch (VendorException e) {
            throw e;
        } catch (WebClientResponseException e) {
            throw apiError("获取实时血糖数据", e);
        } catch (Exception e) {
            throw VendorException.serviceUnavailable("硅基轻享", "获取实时血糖数据", e.getMessage(), e);
        }
    }

    @Override
    public VendorGlucoseData getLatestGlucose(String accessToken, String vendorUserId, VendorSubject subject) {
        List<VendorGlucoseData> history = getHistoricalGlucose(accessToken, vendorUserId, subject);
        if (history.isEmpty()) {
            throw VendorException.noGlucoseData("硅基轻享", subject.getSubjectId());
        }
        return history.get(history.size() - 1);
    }

    @Override
    public List<VendorGlucoseData> getHistoricalGlucose(String accessToken, String vendorUserId, VendorSubject subject) {
        try {
            JsonNode response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/lite-sense-app/follow/glucose")
                            .queryParam("followId", subject.getSubjectId())
                            .build())
                    .headers(h -> buildHeaders(h, accessToken))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null) {
                throw VendorException.responseInvalid("硅基轻享", "获取历史血糖数据", "接口返回为空");
            }

            if (isFailureResponse(response)) {
                String detail = extractErrorDetail(response.toString());
                throw VendorException.responseInvalid(
                        "硅基轻享",
                        "获取历史血糖数据",
                        detail == null || detail.isBlank() ? "接口返回异常" : detail
                );
            }

            JsonNode payload = extractResponseData(response);
            JsonNode glucoseDataList = payload == null ? null : payload.get("glucoseDataList");
            if (glucoseDataList == null || !glucoseDataList.isArray() || glucoseDataList.isEmpty()) {
                return Collections.emptyList();
            }

            // Take first device's data (primary device)
            JsonNode firstDevice = glucoseDataList.get(0);
            JsonNode glucoseInfos = firstDevice.get("glucoseInfos");
            if (glucoseInfos == null || !glucoseInfos.isArray()) {
                return Collections.emptyList();
            }

            List<VendorGlucoseData> readings = new ArrayList<>();
            for (JsonNode point : glucoseInfos) {
                if (!point.has("effective") || !point.get("effective").asBoolean(true)) {
                    continue;
                }
                double glucoseMmol = point.get("v").asDouble();
                long timestampMs = point.get("t").asLong();
                int trendRaw = point.has("s") ? point.get("s").asInt(0) : 0;
                TrendDirection trend = mapSiSensingTrend(trendRaw);

                readings.add(VendorGlucoseData.builder()
                        .glucoseMmol(glucoseMmol)
                        .readingTime(Instant.ofEpochMilli(timestampMs))
                        .trendDirection(trend)
                        .build());
            }
            return readings;

        } catch (VendorException e) {
            throw e;
        } catch (WebClientResponseException e) {
            throw apiError("获取历史血糖数据", e);
        } catch (Exception e) {
            throw VendorException.serviceUnavailable("硅基轻享", "获取历史血糖数据", e.getMessage(), e);
        }
    }

    private VendorException apiError(String operation, WebClientResponseException e) {
        String detail = extractErrorDetail(e.getResponseBodyAsString());
        String errorDetail = (detail == null || detail.isBlank())
                ? "HTTP " + e.getStatusCode().value()
                : detail;
        return VendorException.serviceUnavailable("硅基轻享", operation, errorDetail, e);
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

    private String extractLoginToken(JsonNode response) {
        JsonNode data = extractResponseData(response);
        if (data != null && data.isTextual()) {
            return data.asText();
        }
        if (data != null && data.isObject()) {
            String nested = firstText(data, "token", "accessToken", "authorization", "bearerToken");
            if (nested != null) {
                return nested;
            }
        }
        return firstText(response, "token", "accessToken");
    }

    private boolean isFailureResponse(JsonNode response) {
        if (!isEnvelopeResponse(response)) {
            return false;
        }

        JsonNode success = response.get("success");
        if (success != null && !success.isNull()) {
            return !success.asBoolean(false);
        }

        JsonNode code = response.get("code");
        if (code == null || code.isNull()) {
            return false;
        }

        if (code.isNumber()) {
            return code.asInt() != 200;
        }

        String codeText = code.asText();
        return !"200".equals(codeText) && !"0".equals(codeText) && !"OK".equalsIgnoreCase(codeText);
    }

    private boolean isEnvelopeResponse(JsonNode response) {
        return response != null && (
                response.has("success")
                        || response.has("code")
                        || response.has("msg")
                        || response.has("errorData")
        );
    }

    private JsonNode extractResponseData(JsonNode response) {
        if (response == null || response.isNull()) {
            return null;
        }
        if (!isEnvelopeResponse(response)) {
            return response;
        }
        JsonNode data = response.get("data");
        return data == null || data.isNull() ? null : data;
    }

    private String firstText(JsonNode node, String... fieldNames) {
        if (node == null || node.isNull()) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private String normalizeCredential(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private void buildHeaders(org.springframework.http.HttpHeaders headers, String accessToken) {
        buildCommonHeaders(headers);
        String normalizedToken = VendorTokenNormalizer.normalize(accessToken);
        headers.setBearerAuth(normalizedToken);
    }

    private void buildCommonHeaders(org.springframework.http.HttpHeaders headers) {
        headers.set("version", "2.0");
        headers.set("Sib-Agent", "GJQX&02.24.00.00&iphone&26.2&iPad8,6");
        headers.set("User-Agent", "ECO/3.8 (iPad; iOS 26.2; Scale/2.00)");
        headers.set("Accept", "*/*");
        headers.set("Content-Type", "application/json;charset=UTF-8");
        headers.set("lang", "zh_CN");
        headers.set("TimeZone", "Asia/Shanghai");
    }

    /**
     * SiSensing trend mapping: -1=SingleDown, 0=Flat, 1=SingleUp
     */
    private TrendDirection mapSiSensingTrend(int s) {
        return switch (s) {
            case -1 -> TrendDirection.SINGLE_DOWN;
            case 1 -> TrendDirection.SINGLE_UP;
            default -> TrendDirection.FLAT;
        };
    }
}
