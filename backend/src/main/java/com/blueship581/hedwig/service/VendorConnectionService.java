package com.blueship581.hedwig.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blueship581.hedwig.domain.entity.GatewayUser;
import com.blueship581.hedwig.domain.entity.MonitoredSubject;
import com.blueship581.hedwig.domain.entity.VendorConnection;
import com.blueship581.hedwig.domain.enums.TokenStatus;
import com.blueship581.hedwig.domain.mapper.GatewayUserMapper;
import com.blueship581.hedwig.domain.mapper.MonitoredSubjectMapper;
import com.blueship581.hedwig.domain.mapper.VendorConnectionMapper;
import com.blueship581.hedwig.dto.ConnectByLoginRequest;
import com.blueship581.hedwig.dto.ConnectByTokenRequest;
import com.blueship581.hedwig.dto.VendorConnectionDto;
import com.blueship581.hedwig.exception.ErrorCode;
import com.blueship581.hedwig.exception.ResourceNotFoundException;
import com.blueship581.hedwig.exception.VendorException;
import com.blueship581.hedwig.exception.VendorTokenInvalidException;
import com.blueship581.hedwig.vendor.client.VendorClient;
import com.blueship581.hedwig.vendor.client.VendorClientFactory;
import com.blueship581.hedwig.vendor.model.VendorLoginRequest;
import com.blueship581.hedwig.vendor.model.VendorSubject;
import com.blueship581.hedwig.vendor.model.VendorTokenInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.blueship581.hedwig.exception.AuthException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VendorConnectionService {

  private final VendorConnectionMapper connectionMapper;
  private final MonitoredSubjectMapper subjectMapper;
  private final GatewayUserMapper userMapper;
  private final VendorClientFactory vendorClientFactory;

  @Transactional
  public VendorConnectionDto connectByToken(String username, ConnectByTokenRequest request) {
    log.info(
        "connectByToken: username={}, vendorType={}, accessToken length={}",
        username,
        request.getVendorType(),
        request.getAccessToken() != null ? request.getAccessToken().length() : 0);
    GatewayUser user = findUser(username);
    VendorClient client = vendorClientFactory.getClient(request.getVendorType());
    VendorTokenInfo tokenInfo = client.validateToken(request.getAccessToken());

    log.info(
        "connectByToken: token valid={}, userId={}, expiresAt={}",
        tokenInfo.isValid(),
        tokenInfo.getUserId(),
        tokenInfo.getExpiresAt());

    if (!tokenInfo.isValid()) {
      throw new VendorException(ErrorCode.VENDOR_TOKEN_INVALID, "访问令牌无效或已过期，请重新获取后再连接");
    }

    VendorConnection connection =
        connectionMapper
            .selectList(
                Wrappers.lambdaQuery(VendorConnection.class)
                    .eq(VendorConnection::getGatewayUserId, user.getId())
                    .eq(VendorConnection::getVendorType, request.getVendorType()))
            .stream()
            .findFirst()
            .orElse(
                VendorConnection.builder()
                    .gatewayUserId(user.getId())
                    .vendorType(request.getVendorType())
                    .build());

    connection.setAccessToken(tokenInfo.getToken());
    connection.setVendorUserId(tokenInfo.getUserId());
    connection.setTokenExpiresAt(tokenInfo.getExpiresAt());
    connection.setTokenStatus(TokenStatus.ACTIVE);
    if (connection.getId() == null) {
      connectionMapper.insert(connection);
    } else {
      connectionMapper.updateById(connection);
    }

    // Sync monitored subjects
    syncSubjects(connection, client);

    return toDto(connection);
  }

  @Transactional
  public VendorConnectionDto connectByLogin(String username, ConnectByLoginRequest request) {
    GatewayUser user = findUser(username);
    VendorClient client = vendorClientFactory.getClient(request.getVendorType());

    VendorTokenInfo tokenInfo =
        client.login(
            VendorLoginRequest.builder()
                .username(request.getUsername())
                .password(request.getPassword())
                .build());

    if (!tokenInfo.isValid()) {
      throw new VendorException(ErrorCode.VENDOR_LOGIN_FAILED, "厂商账号登录失败，或返回的访问令牌无效");
    }

    VendorConnection connection =
        connectionMapper
            .selectList(
                Wrappers.lambdaQuery(VendorConnection.class)
                    .eq(VendorConnection::getGatewayUserId, user.getId())
                    .eq(VendorConnection::getVendorType, request.getVendorType()))
            .stream()
            .findFirst()
            .orElse(
                VendorConnection.builder()
                    .gatewayUserId(user.getId())
                    .vendorType(request.getVendorType())
                    .build());

    connection.setAccessToken(tokenInfo.getToken());
    connection.setVendorUserId(tokenInfo.getUserId());
    connection.setTokenExpiresAt(tokenInfo.getExpiresAt());
    connection.setTokenStatus(TokenStatus.ACTIVE);
    if (connection.getId() == null) {
      connectionMapper.insert(connection);
    } else {
      connectionMapper.updateById(connection);
    }

    syncSubjects(connection, client);

    return toDto(connection);
  }

  @Transactional
  public VendorConnectionDto refreshToken(String username, Long connectionId) {
    GatewayUser user = findUser(username);
    VendorConnection connection =
        Optional.ofNullable(connectionMapper.selectById(connectionId))
            .filter(c -> c.getGatewayUserId().equals(user.getId()))
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        ErrorCode.VENDOR_CONNECTION_NOT_FOUND, "设备连接不存在：" + connectionId));

    VendorClient client = vendorClientFactory.getClient(connection.getVendorType());
    VendorTokenInfo tokenInfo = client.validateToken(connection.getAccessToken());
    connection.setTokenExpiresAt(tokenInfo.getExpiresAt());
    connection.setTokenStatus(tokenInfo.isValid() ? TokenStatus.ACTIVE : TokenStatus.EXPIRED);
    connectionMapper.updateById(connection);

    return toDto(connection);
  }

  @Transactional
  public void disconnect(String username, Long connectionId) {
    GatewayUser user = findUser(username);
    VendorConnection connection =
        Optional.ofNullable(connectionMapper.selectById(connectionId))
            .filter(c -> c.getGatewayUserId().equals(user.getId()))
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        ErrorCode.VENDOR_CONNECTION_NOT_FOUND, "设备连接不存在：" + connectionId));

    // Deactivate all subjects for this connection
    subjectMapper
        .selectList(
            Wrappers.lambdaQuery(MonitoredSubject.class)
                .eq(MonitoredSubject::getVendorConnectionId, connection.getId()))
        .forEach(
            s -> {
              s.setIsActive(false);
              subjectMapper.updateById(s);
            });

    connectionMapper.deleteById(connection.getId());
  }

  public List<VendorConnectionDto> getConnections(String username) {
    GatewayUser user = findUser(username);
    return connectionMapper
        .selectList(
            Wrappers.lambdaQuery(VendorConnection.class)
                .eq(VendorConnection::getGatewayUserId, user.getId()))
        .stream()
        .map(this::toDto)
        .collect(Collectors.toList());
  }

  @Transactional
  public void syncSubjects(VendorConnection connection) {
    VendorClient client = vendorClientFactory.getClient(connection.getVendorType());
    syncSubjects(connection, client);
  }

  /**
   * 更新连接级轮询间隔。
   *
   * <p>不同厂商的数据节奏不同：微泰（AiDEX X）1 分钟一个点，欧泰/硅基 5 分钟一个点。允许用户按需调整，
   * 例如把微泰调成 2 分钟以进一步降低请求量。
   *
   * @param seconds 轮询间隔（秒），传 {@code null} 表示恢复厂商默认值
   */
  @Transactional
  public VendorConnectionDto updatePollingInterval(String username, Long connectionId, Integer seconds) {
    GatewayUser user = findUser(username);
    VendorConnection connection =
        Optional.ofNullable(connectionMapper.selectById(connectionId))
            .filter(c -> c.getGatewayUserId().equals(user.getId()))
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        ErrorCode.VENDOR_CONNECTION_NOT_FOUND, "设备连接不存在：" + connectionId));

    if (seconds != null) {
      int min = 30;
      int max = 3600;
      if (seconds < min || seconds > max) {
        throw new IllegalArgumentException("轮询间隔必须在 " + min + " ~ " + max + " 秒之间");
      }
    }

    connection.setPollingIntervalSeconds(seconds);
    connectionMapper.updateById(connection);
    log.info(
        "Polling interval for connection {} ({}) set to {}s",
        connection.getId(),
        connection.getVendorType(),
        seconds == null ? "vendor-default" : seconds);
    return toDto(connection);
  }

  /** 实际生效的轮询间隔（秒）：连接设置优先，否则取厂商默认值。 */
  public static int effectivePollingIntervalSeconds(VendorConnection connection) {
    Integer override = connection.getPollingIntervalSeconds();
    if (override != null && override > 0) {
      return override;
    }
    return connection.getVendorType() == null
        ? 300
        : connection.getVendorType().getDefaultPollingIntervalSeconds();
  }

  private void syncSubjects(VendorConnection connection, VendorClient client) {
    try {
      List<VendorSubject> vendorSubjects =
          client.getMonitoredSubjects(connection.getAccessToken(), connection.getVendorUserId());

      Instant now = Instant.now();
      for (VendorSubject vs : vendorSubjects) {
        Instant sensorExpiresAt =
            vs.getSensorRestSeconds() != null ? now.plusSeconds(vs.getSensorRestSeconds()) : null;

        Optional<MonitoredSubject> existingOpt =
            subjectMapper
                .selectList(
                    Wrappers.lambdaQuery(MonitoredSubject.class)
                        .eq(MonitoredSubject::getVendorConnectionId, connection.getId())
                        .eq(MonitoredSubject::getVendorSubjectId, vs.getSubjectId()))
                .stream()
                .findFirst();

        if (existingOpt.isPresent()) {
          MonitoredSubject existing = existingOpt.get();
          existing.setDisplayName(vs.getDisplayName());
          existing.setVendorDeviceId(vs.getDeviceId());
          existing.setIsActive(true);
          existing.setSensorExpiresAt(sensorExpiresAt);
          subjectMapper.updateById(existing);
        } else {
          subjectMapper.insert(
              MonitoredSubject.builder()
                  .vendorConnectionId(connection.getId())
                  .vendorSubjectId(vs.getSubjectId())
                  .vendorDeviceId(vs.getDeviceId())
                  .displayName(vs.getDisplayName())
                  .isActive(true)
                  .sensorExpiresAt(sensorExpiresAt)
                  .build());
        }
      }
    } catch (VendorTokenInvalidException e) {
      // 令牌失效必须让调用方感知：连接阶段直接报错，轮询阶段由调度器把连接标记为 EXPIRED。
      throw e;
    } catch (Exception e) {
      log.warn("Failed to sync subjects for connection {}: {}", connection.getId(), e.getMessage());
    }
  }

  private GatewayUser findUser(String username) {
    GatewayUser user =
        userMapper.selectOne(
            Wrappers.lambdaQuery(GatewayUser.class).eq(GatewayUser::getUsername, username));
    if (user == null) {
      throw new AuthException(ErrorCode.USER_NOT_FOUND, "用户不存在：" + username);
    }
    return user;
  }

  private VendorConnectionDto toDto(VendorConnection c) {
    MonitoredSubject primarySubject =
        subjectMapper
            .selectList(
                Wrappers.lambdaQuery(MonitoredSubject.class)
                    .eq(MonitoredSubject::getVendorConnectionId, c.getId())
                    .eq(MonitoredSubject::getIsActive, true))
            .stream()
            .min(Comparator.comparing(MonitoredSubject::getId))
            .orElse(null);

    return VendorConnectionDto.builder()
        .id(c.getId())
        .vendorType(c.getVendorType())
        .vendorUserId(c.getVendorUserId())
        .primarySubjectId(primarySubject != null ? primarySubject.getId() : null)
        .primarySubjectName(primarySubject != null ? primarySubject.getDisplayName() : null)
        .tokenStatus(c.getTokenStatus())
        .tokenExpiresAt(c.getTokenExpiresAt())
        .lastSyncedAt(c.getLastSyncedAt())
        .sensorExpiresAt(primarySubject != null ? primarySubject.getSensorExpiresAt() : null)
        .pollingIntervalSeconds(c.getPollingIntervalSeconds())
        .defaultPollingIntervalSeconds(
            c.getVendorType() == null
                ? null
                : c.getVendorType().getDefaultPollingIntervalSeconds())
        .effectivePollingIntervalSeconds(effectivePollingIntervalSeconds(c))
        .build();
  }
}
