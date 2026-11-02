package com.blueship581.hedwig.vendor.client;

import com.blueship581.hedwig.domain.enums.TrendDirection;
import com.blueship581.hedwig.exception.VendorException;
import com.blueship581.hedwig.exception.VendorTokenInvalidException;
import com.blueship581.hedwig.vendor.model.VendorGlucoseData;
import com.blueship581.hedwig.vendor.model.VendorLoginRequest;
import com.blueship581.hedwig.vendor.model.VendorSubject;
import com.blueship581.hedwig.vendor.model.VendorTokenInfo;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link MicroTechClient}, driven by real responses recorded from
 * {@code https://china.pancares.com/backend/aidex-x} (see {@code doc/微泰/微泰-AiDEX-API逆向分析.md}).
 */
class MicroTechClientTest {

  // ── login ──────────────────────────────────────────────────────────────

  @Test
  void loginSendsPasswordAndExtractsToken() {
    AtomicReference<ClientRequest> seen = new AtomicReference<>();
    MicroTechClient client =
        microTechClient(
            builderFor(
                seen,
                request -> {
                  String path = request.url().getPath();
                  if (path.endsWith("/user/loginByPassword")) {
                    return okJson(
                        """
                        {"code":200,"msg":"请求成功","count":0,"data":{
                          "appId":"global","project":"aidex-x","identity":1,
                          "userId":"prov-user-0001",
                          "username":"13200000000",
                          "token":"mt-token-abc","refreshToken":"refresh-abc"}}
                        """);
                  }
                  return Mono.error(new IllegalStateException("Unexpected path: " + path));
                }));

    VendorTokenInfo info =
        client.login(
            VendorLoginRequest.builder().username("13200000000").password("secret").build());

    assertTrue(info.isValid());
    assertEquals("mt-token-abc", info.getToken());
    assertEquals("prov-user-0001", info.getUserId());
    assertNull(info.getExpiresAt());
    assertEquals("/backend/aidex-x/user/loginByPassword", seen.get().url().getPath());
    assertEquals("POST", seen.get().method().name());
  }

  @Test
  void loginFallsBackToPlainPasswordWhenMd5Rejected() {
    AtomicInteger calls = new AtomicInteger();
    MicroTechClient client =
        microTechClient(
            WebClient.builder()
                .exchangeFunction(
                    request -> {
                      int n = calls.incrementAndGet();
                      return n == 1
                          ? okJson("{\"code\":79865,\"msg\":\"用户名密码错误\"}")
                          : okJson(
                              "{\"code\":200,\"data\":{\"token\":\"mt-token\",\"userId\":\"u-1\"}}");
                    }));

    VendorTokenInfo info =
        client.login(
            VendorLoginRequest.builder().username("13200000000").password("secret").build());

    assertTrue(info.isValid());
    assertEquals(2, calls.get(), "md5 attempt + plaintext retry");
  }

  @Test
  void loginMapsWrongCredentialsToActionableMessage() {
    MicroTechClient client =
        microTechClient(
            builderFor(null, request -> okJson("{\"code\":79865,\"msg\":\"用户名密码错误\"}")));

    VendorException error =
        assertThrows(
            VendorException.class,
            () ->
                client.login(
                    VendorLoginRequest.builder().username("13200000000").password("bad").build()));

    assertTrue(error.getMessage().contains("微泰账号或密码错误"), error.getMessage());
  }

  @Test
  void loginReportsMissingPasswordBeforeCallingVendor() {
    MicroTechClient client =
        microTechClient(builderFor(null, request -> Mono.error(new AssertionError("no call"))));

    VendorException error =
        assertThrows(
            VendorException.class,
            () -> client.login(VendorLoginRequest.builder().username("13200000000").build()));

    assertTrue(error.getMessage().contains("密码"), error.getMessage());
  }

  // ── token validation ──────────────────────────────────────────────────

  @Test
  void validateTokenReturnsValidOnUserInfo() {
    MicroTechClient client =
        microTechClient(
            builderFor(
                null,
                request ->
                    okJson(
                        """
                        {"code":200,"data":{"userInformationId":"51e5","email":"13200000000",
                         "phone":"13200000000","userId":"prov-user-0001","name":"小鱼"}}
                        """)));

    VendorTokenInfo info = client.validateToken("Bearer mt-token-abc");

    assertTrue(info.isValid());
    assertEquals("prov-user-0001", info.getUserId());
    assertEquals("mt-token-abc", info.getToken());
  }

  @Test
  void validateTokenReturnsInvalidOnTokenExpiredCode() {
    MicroTechClient client =
        microTechClient(builderFor(null, request -> okJson("{\"code\":801,\"msg\":\"801\"}")));

    assertFalse(client.validateToken("expired-token").isValid());
  }

  @Test
  void latestReadingThrowsTokenInvalidSoSchedulerCanStop() {
    MicroTechClient client =
        microTechClient(builderFor(null, request -> okJson("{\"code\":800,\"msg\":\"800\"}")));

    assertThrows(
        VendorTokenInvalidException.class,
        () -> client.getLatestGlucose("expired-token", "u-1", null));
  }

  // ── latest reading (cgmRecord, glucose is mg/dL) ───────────────────────

  @Test
  void latestReadingConvertsMgdlAndParsesAppTime() {
    MicroTechClient client =
        microTechClient(
            builderFor(
                null,
                request ->
                    okJson(
                        """
                        {"code":200,"msg":"请求成功","count":1,"data":[{
                          "cgmRecordId":"c621","sensorId":"b482","appTime":"2026-11-02 13:29:31",
                          "appTimeZone":"Asia/Shanghai","glucose":207.00,"glucoseIsValid":1,
                          "trendValue":11,"smooth":207}]}
                        """)));

    VendorGlucoseData reading = client.getLatestGlucose("token", "u-1", null);

    assertEquals(11.5, reading.getGlucoseMmol(), 0.05); // 207 mg/dL
    assertEquals(Instant.parse("2026-11-02T05:29:31Z"), reading.getReadingTime()); // Asia/Shanghai
    assertEquals(TrendDirection.NONE, reading.getTrendDirection());
  }

  @Test
  void latestReadingAcceptsRecordsWrapper() {
    MicroTechClient client =
        microTechClient(
            builderFor(
                null,
                request ->
                    okJson(
                        """
                        {"code":200,"data":{"records":[{"glucose":115.2,"appTime":"2026-11-02 08:30:00"}]}}
                        """)));

    VendorGlucoseData reading = client.getLatestGlucose("token", "u-1", null);

    assertEquals(6.4, reading.getGlucoseMmol(), 0.05);
  }

  @Test
  void latestReadingFailsClearlyWhenVendorHasNoRecords() {
    MicroTechClient client =
        microTechClient(builderFor(null, request -> okJson("{\"code\":200,\"data\":[]}")));

    VendorException error =
        assertThrows(VendorException.class, () -> client.getLatestGlucose("token", "u-1", null));
    assertTrue(error.getMessage().contains("暂无血糖数据"), error.getMessage());
  }

  @Test
  void latestReadingIgnoresGenericSettingValues() {
    MicroTechClient client =
        microTechClient(
            builderFor(
                null,
                request ->
                    okJson(
                        """
                        {"code":200,"data":[{"item":"targetRange","value":10,
                          "glucose":203.0,"appTime":"2026-11-02 13:24:31"}]}
                        """)));

    VendorGlucoseData reading = client.getLatestGlucose("token", "u-1", null);

    assertEquals(11.3, reading.getGlucoseMmol(), 0.05);
  }

  // ── history ───────────────────────────────────────────────────────────

  @Test
  void historyParsesRecordsSkipsInvalidAndSortsAscending() {
    MicroTechClient client =
        microTechClient(
            builderFor(
                null,
                request ->
                    okJson(
                        """
                        {"code":200,"count":3,"data":[
                          {"glucose":207.0,"appTime":"2026-11-02 13:29:31","glucoseIsValid":1},
                          {"glucose":203.0,"appTime":"2026-11-02 13:24:31","glucoseIsValid":1},
                          {"glucose":999.0,"appTime":"2026-11-02 13:30:31","glucoseIsValid":0}
                        ]}
                        """)));

    List<VendorGlucoseData> readings =
        client.getHistoricalGlucose(
            "token", "u-1", VendorSubject.builder().subjectId("u-1").build());

    assertEquals(2, readings.size());
    assertEquals(11.3, readings.get(0).getGlucoseMmol(), 0.05); // 13:24 最早
    assertEquals(11.5, readings.get(1).getGlucoseMmol(), 0.05);
  }

  @Test
  void historyReturnsEmptyListWhenVendorHasNoRecords() {
    MicroTechClient client =
        microTechClient(builderFor(null, request -> okJson("{\"code\":200,\"data\":[]}")));

    assertTrue(client.getHistoricalGlucose("token", "u-1", null).isEmpty());
  }

  // ── subjects (授权/关注 模型) ─────────────────────────────────────────

  @Test
  void subjectsComeFromAuthorizationList() {
    MicroTechClient client =
        microTechClient(
            builderFor(
                null,
                request -> {
                  String path = request.url().getPath();
                  if (path.endsWith("/userAuthorization/findUserAuthorizationList")) {
                    return okJson(
                        """
                        {"code":200,"count":1,"data":[{
                          "userAuthorizationId":"af75","dataProviderId":"prov-user-0001",
                          "dataReaderId":"df6c","providerAlias":null,
                          "providerUserName":"13200000000"}]}
                        """);
                  }
                  if (path.endsWith("/user/getUserInfo")) {
                    return okJson("{\"code\":200,\"data\":{\"userId\":\"00be24\",\"name\":\"小鱼\"}}");
                  }
                  if (path.endsWith("/cgmDevice/getUserDeviceInfo")) {
                    return okJson(
                        """
                        {"code":200,"data":{"deviceInfo":{"sensorId":"b482","et":15,
                          "sensorStartUp":"2026-10-31 13:34:31+0000","name":"AiDEX X"}}}
                        """);
                  }
                  return Mono.error(new IllegalStateException("Unexpected path: " + path));
                }));

    List<VendorSubject> subjects = client.getMonitoredSubjects("token", null);

    assertEquals(1, subjects.size());
    assertEquals("prov-user-0001", subjects.get(0).getSubjectId());
    assertEquals("小鱼", subjects.get(0).getDisplayName());
    assertEquals("b482", subjects.get(0).getDeviceId());
    assertNotNull(subjects.get(0).getSensorRestSeconds());
  }

  @Test
  void subjectsFallBackToSelfWhenNoAuthorization() {
    MicroTechClient client =
        microTechClient(
            builderFor(
                null,
                request -> {
                  String path = request.url().getPath();
                  if (path.endsWith("/userAuthorization/findUserAuthorizationList")) {
                    return okJson("{\"code\":200,\"count\":0,\"data\":[]}");
                  }
                  if (path.endsWith("/user/getUserInfo")) {
                    return okJson(
                        """
                        {"code":200,"data":{"userId":"prov-user-0001","name":"小鱼"}}
                        """);
                  }
                  if (path.endsWith("/cgmDevice/getUserDeviceInfo")) {
                    return okJson("{\"code\":200,\"data\":{}}");
                  }
                  return Mono.error(new IllegalStateException("Unexpected path: " + path));
                }));

    List<VendorSubject> subjects = client.getMonitoredSubjects("token", null);

    assertEquals(1, subjects.size());
    assertEquals("prov-user-0001", subjects.get(0).getSubjectId());
    assertEquals("小鱼", subjects.get(0).getDisplayName());
  }

  @Test
  void latestReadingRequestsProviderDataByUserId() {
    AtomicReference<ClientRequest> seen = new AtomicReference<>();
    MicroTechClient client =
        microTechClient(
            builderFor(
                seen,
                request ->
                    okJson(
                        """
                        {"code":200,"data":[{"glucose":207.0,"appTime":"2026-11-02 13:29:31"}]}
                        """)));

    client.getLatestGlucose(
        "token",
        "reader-id",
        VendorSubject.builder().subjectId("provider-id").build());

    Map<String, String> params = new LinkedHashMap<>();
    seen.get().url().getQuery().split("&");
    for (String pair : seen.get().url().getQuery().split("&")) {
      String[] kv = pair.split("=", 2);
      params.put(kv[0], kv.length > 1 ? kv[1] : "");
    }
    assertEquals("provider-id", params.get("userId"));
    assertEquals("1", params.get("pageSize"));
  }

  // ── helpers ───────────────────────────────────────────────────────────

  private MicroTechClient microTechClient(WebClient.Builder builder) {
    return new MicroTechClient(builder, MicroTechClient.DEFAULT_BASE_URL);
  }

  private WebClient.Builder builderFor(
      AtomicReference<ClientRequest> seen, ExchangeFunction handler) {
    return WebClient.builder()
        .exchangeFunction(
            request -> {
              if (seen != null) {
                seen.set(request);
              }
              return handler.exchange(request);
            });
  }

  private Mono<ClientResponse> okJson(String body) {
    return Mono.just(
        ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
  }
}
