package com.blueship581.hedwig.vendor.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.blueship581.hedwig.domain.enums.TrendDirection;
import com.blueship581.hedwig.exception.ErrorCode;
import com.blueship581.hedwig.exception.VendorException;
import com.blueship581.hedwig.exception.VendorTokenInvalidException;
import com.blueship581.hedwig.vendor.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Client for 微泰 AiDEX X (MicroTech) CGM vendor API.
 *
 * <p>Base URL: {@code https://gateway-xj.pancares.com/backend/aidex-x}
 *
 * <p>Auth: response envelope {@code {code,msg,count,data}}, success is {@code code == 200}, token
 * invalid codes are 800..807. The token travels in the {@code token} request header.
 *
 * <p>Login is a plaintext JSON call: {@code POST user/loginByPassword} with
 * {@code {"username": phone, "password": password}}.
 *
 * <p>See {@code doc/微泰/微泰-AiDEX-API逆向分析.md} for the reverse-engineering notes this client is
 * based on.
 */
@Slf4j
@Component
public class MicroTechClient implements VendorClient {

  public static final String DEFAULT_BASE_URL = "https://china.pancares.com/backend/aidex-x";

  /** 微泰没有「关注他人」概念时使用的兜底对象 ID。 */
  private static final String DEFAULT_SUBJECT_ID = "self";

  private static final int CODE_SUCCESS = 200;

  /** 账号或密码错误（线上实测）。 */
  private static final int CODE_WRONG_CREDENTIALS = 79865;

  /** App 内 MT_NET_CODE_TOKEN_INVALID 的取值。 */
  private static final Set<Integer> TOKEN_INVALID_CODES =
      Set.of(800, 801, 802, 803, 804, 805, 806, 807);

  private static final double MGDL_PER_MMOL = 18.0182;

  private static final ZoneId VENDOR_ZONE = ZoneId.of("Asia/Shanghai");

  private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS =
      List.of(
          DateTimeFormatter.ISO_LOCAL_DATE_TIME,
          DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
          DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
          DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
          DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

  private static final List<String> MMOL_FIELDS =
      List.of(
          "mmolValue", "mmol", "glucoseMmol", "currentMmol", "mmolL", "bloodGlucoseMmol",
          "sgvMmol", "glucoseValueMmol");

  private static final List<String> MGDL_FIELDS =
      List.of(
          "mgValue", "mgdl", "glucoseMgdl", "sgv", "glucoseMg", "currentMg", "glucoseValueMgdl");

  private static final List<String> GENERIC_GLUCOSE_FIELDS =
      List.of(
          "glucose",
          "currentGlucose",
          "latestGlucose",
          "glucoseValue",
          "bloodGlucose",
          "measureValue",
          "bg",
          "ct");

  private static final List<String> TIME_FIELDS =
      List.of(
          "deviceTime",
          "time",
          "glucoseTime",
          "appTime",
          "updateTime",
          "lastSignalTime",
          "measureTime",
          "dataTime",
          "eventTime",
          "recordTime",
          "createTime",
          "ts",
          "timestamp");

  private final WebClient webClient;
  private final String baseUrl;

  /**
   * 服务端比对的是「密码明文」还是「密码 MD5」，不同部署/版本不一致；登录成功一次后记住，避免每次都多打一次请求。
   */
  private volatile boolean passwordAsMd5 = true;

  public MicroTechClient(
      WebClient.Builder webClientBuilder,
      @Value("${microtech.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl) {
    this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
    this.webClient =
        webClientBuilder
            .baseUrl(this.baseUrl)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8")
            .defaultHeader(HttpHeaders.ACCEPT, "*/*")
            .defaultHeader(
                HttpHeaders.USER_AGENT, "AiDEX-X/1.16.2 (iPhone; iOS 18.0; Scale/3.00)")
            .exchangeStrategies(
                ExchangeStrategies.builder()
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                    .build())
            .build();
  }

  // ════════════════════════════════════════════════════════════════════════
  // VendorClient
  // ════════════════════════════════════════════════════════════════════════

  @Override
  public VendorTokenInfo validateToken(String accessToken) {
    String token = VendorTokenNormalizer.normalize(accessToken);
    if (token == null || token.isBlank()) {
      return VendorTokenInfo.builder().valid(false).build();
    }

    try {
      JSONObject data = unwrap(get("/user/getUserInfo", token, null, "校验访问令牌"));
      String userId = data == null ? null : firstText(data, "pkid", "uid", "userId", "id");
      return VendorTokenInfo.builder()
          .token(token)
          .userId(userId)
          .expiresAt(null)
          .valid(true)
          .build();
    } catch (VendorTokenInvalidException e) {
      log.debug("[MicroTech] token rejected while validating: {}", e.getMessage());
      return VendorTokenInfo.builder().valid(false).build();
    } catch (VendorException e) {
      throw e;
    } catch (Exception e) {
      throw new VendorException("微泰访问令牌校验失败，请稍后重试", e);
    }
  }

  @Override
  public VendorTokenInfo login(VendorLoginRequest loginRequest) {
    String username = trimToNull(loginRequest.getUsername());
    String password = loginRequest.getPassword();

    if (username == null) {
      throw new VendorException(ErrorCode.VENDOR_LOGIN_FAILED, "请输入微泰账号（手机号）");
    }
    if (password == null || password.isBlank()) {
      throw new VendorException(ErrorCode.VENDOR_LOGIN_FAILED, "请输入微泰登录密码");
    }

    JSONObject envelope;
    try {
      // 微泰 App 在部分版本里对密码做客户端 MD5 后再提交（MTLoginVCNetwork.net_login 会调用
      // CryptoSwift Digest.md5 + toHexString）。服务端究竟比对明文还是 MD5，不同部署/版本并不一致，
      // 因此这里按「先按上次成功的形态，失败再换另一种」处理，并记住成功形态，后续登录不再多打一次请求。
      boolean hashed = passwordAsMd5;
      envelope = attemptLogin(username, hashed ? md5Hex(password) : password, "账号密码登录");
      if (envelope.getIntValue("code", -1) == CODE_WRONG_CREDENTIALS) {
        boolean retryHashed = !hashed;
        JSONObject retry =
            attemptLogin(
                username,
                retryHashed ? md5Hex(password) : password,
                retryHashed ? "账号密码登录（MD5）" : "账号密码登录（明文）");
        if (retry.getIntValue("code", -1) == CODE_SUCCESS) {
          passwordAsMd5 = retryHashed;
          log.info("[MicroTech] login format detected: password sent as {}", retryHashed ? "md5" : "plain");
          envelope = retry;
        } else {
          envelope = retry;
        }
      }
      int code = envelope.getIntValue("code", -1);
      if (code != CODE_SUCCESS) {
        log.warn(
            "[MicroTech] login rejected: username={}, code={}, msg={}, baseUrl={}, triedPlainAndMd5={}",
            maskUsername(username),
            code,
            envelope.getString("msg"),
            baseUrl,
            code == CODE_WRONG_CREDENTIALS);
        throw new VendorException(
            ErrorCode.VENDOR_LOGIN_FAILED, loginErrorMessage(code, envelope.getString("msg")));
      }

      JSONObject data = unwrap(envelope);
      String token =
          data == null
              ? null
              : firstText(data, "token", "accessToken", "access_token", "authorization");
      if (token == null || token.isBlank()) {
        throw new VendorException(
            ErrorCode.VENDOR_LOGIN_FAILED, "微泰登录成功，但响应中没有访问令牌");
      }

      token = VendorTokenNormalizer.normalize(token);
      String userId =
          firstText(data, "pkid", "uid", "userId", "id", "userInfoId");

      // getUserInfo 能拿到更可靠的用户标识；失败不影响登录本身。
      if (userId == null) {
        try {
          JSONObject userInfo = unwrap(get("/user/getUserInfo", token, null, "获取用户信息"));
          userId = userInfo == null ? null : firstText(userInfo, "pkid", "uid", "userId", "id");
        } catch (Exception e) {
          log.debug("[MicroTech] getUserInfo after login failed: {}", e.getMessage());
        }
      }

      log.info("[MicroTech] login ok, userId={}, token length={}", userId, token.length());
      return VendorTokenInfo.builder()
          .token(token)
          .userId(userId)
          .expiresAt(null)
          .valid(true)
          .build();

    } catch (VendorException e) {
      throw e;
    } catch (Exception e) {
      throw new VendorException(ErrorCode.VENDOR_LOGIN_FAILED, "微泰账号密码登录失败", e);
    }
  }

  /**
   * 用指定密码形态调用一次登录接口。
   *
   * @param passwordValue 明文密码或 MD5 十六进制串
   */
  private JSONObject attemptLogin(String username, String passwordValue, String operation) {
    JSONObject payload = new JSONObject();
    payload.put("username", username);
    payload.put("password", passwordValue);
    return post("/user/loginByPassword", null, payload, operation);
  }


  /** 手机号只露头尾，避免把完整账号写进日志。 */
  private String maskUsername(String username) {
    if (username == null || username.length() <= 4) {
      return "***";
    }
    return username.substring(0, 3) + "****" + username.substring(username.length() - 4);
  }

  /** 微泰 App 端使用的是 CryptoSwift 的 MD5 + 小写十六进制。 */
  private String md5Hex(String raw) {
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
      byte[] hash = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new VendorException("微泰登录密码摘要计算失败", e);
    }
  }

  /**
   * 把微泰登录接口的 code 翻译成能指导用户操作的中文提示。
   *
   * <p>code 取值来自线上探测：100006 缺少用户名、100004 缺少密码、79865 账号或密码错误、500 服务端拒绝。
   */
  private String loginErrorMessage(int code, String rawMessage) {
    return switch (code) {
      case 100006 -> "请输入微泰账号（手机号）";
      case 100004 -> "请输入微泰登录密码";
      case 79865 -> "微泰账号或密码错误，请确认是 AiDEX X App 的登录密码";
      case 500 -> "微泰登录请求被拒绝，请稍后重试或改用短信验证码登录 App 后确认账号状态";
      default ->
          "微泰登录失败（code="
              + code
              + (rawMessage != null && !rawMessage.isBlank() ? "：" + rawMessage : "")
              + "）";
    };
  }

  @Override
  public List<VendorSubject> getMonitoredSubjects(String accessToken, String vendorUserId) {
    // 微泰有「授权/关注」模型：读者账号调用 userAuthorization/findUserAuthorizationList 拿到
    // 授权给自己的提供者列表，再用 ?userId=<dataProviderId> 读取对方的数据。
    // 若没有任何授权记录，则退化为「本人设备」单对象模式。
    List<VendorSubject> subjects = new ArrayList<>();
    try {
      JSONObject data =
          unwrap(
              get(
                  "/userAuthorization/findUserAuthorizationList",
                  accessToken,
                  null,
                  "获取授权列表"));
      JSONArray items = extractRecordArray(data);
      if (items != null) {
        for (int i = 0; i < items.size(); i++) {
          JSONObject item = items.getJSONObject(i);
          if (item == null) {
            continue;
          }
          String providerId = firstText(item, "dataProviderId", "providerId", "userId");
          if (providerId == null || providerId.isBlank()) {
            continue;
          }
          String alias = firstText(item, "providerAlias");
          String fallbackName = firstText(item, "providerUserName", "providerName", "providerPhone");
          VendorSubject subject = buildSubject(accessToken, providerId, alias, fallbackName);
          if (subject != null) {
            subjects.add(subject);
          }
        }
      }
    } catch (VendorTokenInvalidException e) {
      throw e;
    } catch (Exception e) {
      log.warn("[MicroTech] findUserAuthorizationList failed: {}", e.getMessage());
    }

    if (!subjects.isEmpty()) {
      return subjects;
    }

    // 没有授权关系 → 读自己账号的设备
    String selfId = vendorUserId;
    String selfName = null;
    try {
      JSONObject data = unwrap(get("/user/getUserInfo", accessToken, null, "获取监测对象"));
      if (data != null) {
        String resolved = firstText(data, "userId", "userInformationId", "pkid", "uid", "id");
        if (resolved != null && !resolved.isBlank()) {
          selfId = resolved;
        }
        selfName = firstText(data, "name", "nickname", "phone", "email");
      }
    } catch (VendorTokenInvalidException e) {
      throw e;
    } catch (Exception e) {
      log.warn("[MicroTech] getUserInfo failed, fallback to vendorUserId: {}", e.getMessage());
    }

    VendorSubject self = buildSubject(accessToken, selfId, null, selfName);
    return self == null ? List.of() : List.of(self);
  }

  /**
   * 组装一个监测对象：读该用户的设备信息补上 sensorId 与传感器剩余时间（失败不影响主流程）。
   *
   * @param providerUserId 数据提供者 ID；为空表示登录账号自己
   */
  private VendorSubject buildSubject(
      String accessToken, String providerUserId, String alias, String fallbackName) {
    String displayName = alias;
    String subjectId = (providerUserId == null || providerUserId.isBlank())
        ? DEFAULT_SUBJECT_ID
        : providerUserId;

    // 家人没设置备注时，用对方的真实昵称（/user/getUserInfo?userId=... 可读到）
    if ((displayName == null || displayName.isBlank()) && !DEFAULT_SUBJECT_ID.equals(subjectId)) {
      try {
        JSONObject info =
            unwrap(
                get(
                    "/user/getUserInfo",
                    accessToken,
                    Map.of("userId", subjectId),
                    "获取家人信息"));
        if (info != null) {
          String name = firstText(info, "name", "nickname");
          if (name != null && !name.isBlank()) {
            displayName = name;
          }
        }
      } catch (VendorTokenInvalidException e) {
        throw e;
      } catch (Exception e) {
        log.debug("[MicroTech] getUserInfo for {} failed: {}", subjectId, e.getMessage());
      }
    }

    String sensorId = null;
    Long sensorRestSeconds = null;
    try {
      Map<String, String> query = new LinkedHashMap<>();
      if (!DEFAULT_SUBJECT_ID.equals(subjectId)) {
        query.put("userId", subjectId);
      }
      JSONObject device =
          unwrap(get("/cgmDevice/getUserDeviceInfo", accessToken, query, "获取设备信息"));
      JSONObject info = device == null ? null : device.getJSONObject("deviceInfo");
      if (info != null) {
        sensorId = firstText(info, "sensorId", "deviceSn");
        sensorRestSeconds = computeSensorRestSeconds(info);
        if (displayName == null || displayName.isBlank()) {
          displayName = firstText(info, "name");
        }
      }
    } catch (VendorTokenInvalidException e) {
      throw e;
    } catch (Exception e) {
      log.debug("[MicroTech] getUserDeviceInfo failed for {}: {}", subjectId, e.getMessage());
    }

    if (displayName == null || displayName.isBlank()) {
      displayName = fallbackName;
    }
    if (displayName == null || displayName.isBlank()) {
      displayName = DEFAULT_SUBJECT_ID.equals(subjectId) ? "微泰血糖仪" : "微泰家人";
    }

    return VendorSubject.builder()
        .subjectId(subjectId)
        .deviceId(sensorId)
        .displayName(displayName)
        .latestGlucoseMmol(null)
        .sensorRestSeconds(sensorRestSeconds)
        .build();
  }

  @Override
  public VendorGlucoseData getLatestGlucose(
      String accessToken, String vendorUserId, VendorSubject subject) {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("pageNum", "1");
    query.put("pageSize", "1");
    String providerId = resolveProviderId(vendorUserId, subject);
    if (providerId != null) {
      query.put("userId", providerId);
    }

    JSONObject data;
    try {
      data =
          unwrap(
              get(
                  "/cgmRecord/getCgmRecordsByPageInfoWithoutRaw",
                  accessToken,
                  query,
                  "获取实时血糖"));
    } catch (VendorException e) {
      throw e;
    } catch (Exception e) {
      throw new VendorException("获取微泰实时血糖失败", e);
    }

    JSONArray records = extractRecordArray(data);
    if (records == null || records.isEmpty()) {
      throw new VendorException(ErrorCode.GLUCOSE_NO_DATA, "微泰暂无血糖数据（可能传感器未佩戴或未上传）");
    }
    VendorGlucoseData reading = extractReading(records.getJSONObject(0), 0);
    if (reading == null) {
      throw new VendorException(ErrorCode.VENDOR_API_ERROR, "微泰血糖记录缺少可用数值");
    }
    return reading;
  }

  @Override
  public List<VendorGlucoseData> getHistoricalGlucose(
      String accessToken, String vendorUserId, VendorSubject subject) {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("pageNum", "1");
    query.put("pageSize", "1440");
    String providerId = resolveProviderId(vendorUserId, subject);
    if (providerId != null) {
      query.put("userId", providerId);
    }

    JSONObject data;
    try {
      data = unwrap(
          get("/cgmRecord/getCgmRecordsByPageInfoWithoutRaw", accessToken, query, "获取历史血糖"));
    } catch (VendorException e) {
      throw e;
    } catch (Exception e) {
      throw new VendorException("获取微泰历史血糖数据失败", e);
    }

    JSONArray records = extractRecordArray(data);
    if (records == null || records.isEmpty()) {
      return List.of();
    }

    List<VendorGlucoseData> readings = new ArrayList<>(records.size());
    for (int i = 0; i < records.size(); i++) {
      JSONObject record = records.getJSONObject(i);
      if (record == null) {
        continue;
      }
      if (record.containsKey("glucoseIsValid") && !record.getBooleanValue("glucoseIsValid", true)) {
        continue;
      }
      VendorGlucoseData reading = extractReading(record, 0);
      if (reading != null) {
        readings.add(reading);
      }
    }

    readings.sort(Comparator.comparing(VendorGlucoseData::getReadingTime));
    return readings;
  }

  // ════════════════════════════════════════════════════════════════════════
  // HTTP plumbing
  // ════════════════════════════════════════════════════════════════════════

  private JSONObject get(
      String path, String token, Map<String, String> query, String operation) {
    try {
      String body =
          webClient
              .get()
              .uri(
                  uriBuilder -> {
                    uriBuilder.path(path);
                    if (query != null) {
                      query.forEach(uriBuilder::queryParam);
                    }
                    return uriBuilder.build();
                  })
              .headers(h -> applyToken(h, token))
              .retrieve()
              .bodyToMono(String.class)
              .block();
      return parseEnvelope(body, operation);
    } catch (WebClientResponseException e) {
      throw apiError(operation, e);
    }
  }

  private JSONObject post(String path, String token, Object payload, String operation) {
    try {
      String body =
          webClient
              .post()
              .uri(path)
              .headers(h -> applyToken(h, token))
              .body(Mono.just(JSON.toJSONString(payload)), String.class)
              .retrieve()
              .bodyToMono(String.class)
              .block();
      return parseEnvelope(body, operation);
    } catch (WebClientResponseException e) {
      throw apiError(operation, e);
    }
  }

  private void applyToken(HttpHeaders headers, String token) {
    if (token != null && !token.isBlank()) {
      headers.set("token", VendorTokenNormalizer.normalize(token));
    }
  }

  private JSONObject parseEnvelope(String body, String operation) {
    if (body == null || body.isBlank()) {
      throw new VendorException(ErrorCode.VENDOR_API_EMPTY, "微泰接口返回为空：" + operation);
    }
    try {
      return JSON.parseObject(body);
    } catch (Exception e) {
      log.warn("[MicroTech] non-JSON response for {}: {}", operation, abbreviate(body));
      throw new VendorException(
          ErrorCode.VENDOR_API_ERROR, "微泰接口返回格式异常（" + operation + "）");
    }
  }

  /**
   * 校验 code 并取出 data。
   *
   * @return data 节点（可能为 null）
   */
  private JSONObject unwrap(JSONObject envelope) {
    if (envelope == null) {
      throw new VendorException(ErrorCode.VENDOR_API_EMPTY, "微泰接口返回为空");
    }
    int code = envelope.getIntValue("code", -1);

    if (code == CODE_SUCCESS) {
      Object data = envelope.get("data");
      if (data instanceof JSONObject obj) {
        return obj;
      }
      if (data instanceof JSONArray array) {
        // 有些接口 data 直接是数组，包一层方便上层统一处理
        JSONObject wrapper = new JSONObject();
        wrapper.put("records", array);
        return wrapper;
      }
      return null;
    }

    String message = envelope.getString("msg");
    if (TOKEN_INVALID_CODES.contains(code)) {
      if (code == 802) {
        throw new VendorTokenInvalidException(
            "微泰账号已在其它设备（手机 App）登录，网关会话被顶下线；"
                + "如需继续监控，请重新连接（监控期间尽量不要同时登录 App）");
      }
      throw new VendorTokenInvalidException(
          "微泰访问令牌已失效（code=" + code + (message != null ? "：" + message : "") + "），请重新连接微泰账号");
    }

    throw new VendorException(
        ErrorCode.VENDOR_API_ERROR,
        "微泰接口返回异常（code=" + code + (message != null ? "：" + message : "") + "）");
  }

  private VendorException apiError(String operation, WebClientResponseException e) {
    int status = e.getStatusCode().value();
    String detail = abbreviate(e.getResponseBodyAsString());
    return new VendorException(
        "微泰接口请求失败（" + operation + "，HTTP " + status + "）" + (detail.isEmpty() ? "" : "：" + detail),
        e);
  }

  // ════════════════════════════════════════════════════════════════════════
  // Reading extraction
  // ════════════════════════════════════════════════════════════════════════

  /**
   * 在返回体里找出「最像一次血糖读数」的节点。
   *
   * <p>微泰 App 首页数据（{@code realTime/appIndexMsg}）把当前值放在 {@code userTrend} 一类的子节点里，
   * 但不同版本/不同接口的嵌套层级并不完全一致，因此这里按「同时包含血糖字段与时间字段」打分递归挑选，
   * 避免写死路径导致版本升级后直接失效。
   */
  private VendorGlucoseData extractReading(JSONObject node, int depth) {
    if (node == null || depth > 6) {
      return null;
    }

    Candidate best = new Candidate();
    inspect(node, depth, best);

    if (best.glucoseMmol == null) {
      return null;
    }

    // 没有时间戳时按分钟取整，保证同一分钟内的重复轮询不会产生重复行（Hedwig 以 readingTime 去重）。
    Instant time =
        best.readingTime != null
            ? best.readingTime
            : Instant.now().truncatedTo(ChronoUnit.MINUTES);
    return VendorGlucoseData.builder()
        .glucoseMmol(round(best.glucoseMmol))
        .readingTime(time)
        .trendDirection(TrendDirection.NONE)
        .build();
  }

  private void inspect(JSONObject node, int depth, Candidate best) {
    if (node == null || depth > 6) {
      return;
    }

    GlucoseHit glucose = extractGlucose(node);
    Instant time = extractTime(node);

    if (glucose != null) {
      // 显式 mmol/mg 字段优先于泛化字段，避免把设置项里的 value 误当成血糖。
      int score = glucose.confidence * 10 + (time != null ? 5 : 0) + depth;
      if (score > best.score) {
        best.score = score;
        best.glucoseMmol = glucose.mmol;
        best.readingTime = time;
      }
    }

    for (String key : node.keySet()) {
      Object child = node.get(key);
      if (child instanceof JSONObject childObj) {
        inspect(childObj, depth + 1, best);
      } else if (child instanceof JSONArray childArray) {
        for (int i = 0; i < childArray.size(); i++) {
          Object item = childArray.get(i);
          if (item instanceof JSONObject itemObj) {
            inspect(itemObj, depth + 1, best);
          }
        }
      }
    }
  }

  private GlucoseHit extractGlucose(JSONObject node) {
    for (String field : MMOL_FIELDS) {
      Double value = readDouble(node, field);
      if (isPlausibleMmol(value)) {
        return new GlucoseHit(value, 3);
      }
    }
    for (String field : MGDL_FIELDS) {
      Double value = readDouble(node, field);
      if (isPlausibleMgdl(value)) {
        return new GlucoseHit(value / MGDL_PER_MMOL, 3);
      }
    }
    for (String field : GENERIC_GLUCOSE_FIELDS) {
      Double value = readDouble(node, field);
      if (isPlausibleMmol(value)) {
        return new GlucoseHit(value, 1);
      }
      if (isPlausibleMgdl(value)) {
        return new GlucoseHit(value / MGDL_PER_MMOL, 1);
      }
    }
    return null;
  }



  /**
   * 数据提供者 ID：授权/关注模式下是 dataProviderId，单账号模式返回 null（即读自己）。
   */
  private String resolveProviderId(String vendorUserId, VendorSubject subject) {
    String id = subject != null ? subject.getSubjectId() : null;
    if (id == null || id.isBlank()) {
      id = vendorUserId;
    }
    if (id == null || id.isBlank() || DEFAULT_SUBJECT_ID.equals(id)) {
      return null;
    }
    return id;
  }

  /**
   * 传感器剩余秒数 = sensorStartUp + et 天 − now。字段缺失或解析失败时返回 null（前端不展示剩余时间）。
   */
  private Long computeSensorRestSeconds(JSONObject info) {
    String startUp = firstText(info, "sensorStartUp", "startUpTime", "startTime");
    Integer lifeDays = info.getInteger("et");
    if (startUp == null || lifeDays == null || lifeDays <= 0) {
      return null;
    }
    try {
      java.time.OffsetDateTime start =
          java.time.OffsetDateTime.parse(
              startUp.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZ"));
      long endEpoch = start.plusDays(lifeDays).toInstant().getEpochSecond();
      long rest = endEpoch - Instant.now().getEpochSecond();
      return rest > 0 ? rest : 0L;
    } catch (Exception e) {
      log.debug("[MicroTech] cannot parse sensorStartUp={}: {}", startUp, e.getMessage());
      return null;
    }
  }

  private Instant extractTime(JSONObject node) {
    for (String field : TIME_FIELDS) {
      Instant parsed = parseTime(node.get(field));
      if (parsed != null) {
        return parsed;
      }
    }
    return null;
  }

  private JSONArray extractRecordArray(JSONObject data) {
    if (data == null) {
      return null;
    }
    for (String field : List.of("records", "list", "rows", "items", "data", "content")) {
      JSONArray array = data.getJSONArray(field);
      if (array != null) {
        return array;
      }
    }
    // 有的接口把数组直接放在 data 上，unwrap 时会包成 records
    return data.getJSONArray("records");
  }

  // ════════════════════════════════════════════════════════════════════════
  // Small helpers
  // ════════════════════════════════════════════════════════════════════════

  private Double readDouble(JSONObject node, String field) {
    Object raw = node.get(field);
    if (raw == null) {
      return null;
    }
    if (raw instanceof Number number) {
      return number.doubleValue();
    }
    if (raw instanceof String text && !text.isBlank()) {
      try {
        return Double.parseDouble(text.trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private boolean isPlausibleMmol(Double value) {
    return value != null && value > 0.5 && value < 40;
  }

  private boolean isPlausibleMgdl(Double value) {
    return value != null && value >= 40 && value < 800;
  }

  private Instant parseTime(Object raw) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof Number number) {
      return fromEpoch(number.longValue());
    }
    String text = String.valueOf(raw).trim();
    if (text.isEmpty()) {
      return null;
    }
    if (text.chars().allMatch(Character::isDigit)) {
      try {
        return fromEpoch(Long.parseLong(text));
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
      try {
        return LocalDateTime.parse(text, formatter).atZone(VENDOR_ZONE).toInstant();
      } catch (Exception ignored) {
        // try next
      }
    }
    try {
      return Instant.parse(text);
    } catch (Exception ignored) {
      return null;
    }
  }

  private Instant fromEpoch(long value) {
    if (value <= 0) {
      return null;
    }
    // 微秒 / 毫秒 / 秒 三种都可能出现
    if (value > 1_000_000_000_000_000L) {
      return Instant.ofEpochMilli(value / 1000);
    }
    if (value > 1_000_000_000_000L) {
      return Instant.ofEpochMilli(value);
    }
    if (value > 1_000_000_000L) {
      return Instant.ofEpochSecond(value);
    }
    return null;
  }

  private String firstText(JSONObject node, String... fieldNames) {
    if (node == null) {
      return null;
    }
    for (String field : fieldNames) {
      String value = node.getString(field);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private double round(Double value) {
    return Math.round(value * 10.0) / 10.0;
  }

  private String abbreviate(String body) {
    if (body == null) {
      return "";
    }
    String singleLine = body.replaceAll("\\s+", " ");
    return singleLine.length() > 200 ? singleLine.substring(0, 200) + "..." : singleLine;
  }

  /** 一次血糖字段命中的结果；confidence 越高表示字段语义越明确。 */
  private record GlucoseHit(double mmol, int confidence) {}

  private static final class Candidate {
    int score = Integer.MIN_VALUE;
    Double glucoseMmol;
    Instant readingTime;
  }
}
