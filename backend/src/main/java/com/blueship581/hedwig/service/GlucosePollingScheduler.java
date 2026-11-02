package com.blueship581.hedwig.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blueship581.hedwig.domain.entity.MonitoredSubject;
import com.blueship581.hedwig.domain.entity.VendorConnection;
import com.blueship581.hedwig.domain.enums.TokenStatus;
import com.blueship581.hedwig.domain.mapper.MonitoredSubjectMapper;
import com.blueship581.hedwig.domain.mapper.VendorConnectionMapper;
import com.blueship581.hedwig.exception.VendorTokenInvalidException;
import com.blueship581.hedwig.vendor.client.OttaiClient;
import com.blueship581.hedwig.vendor.client.SiSensingClient;
import com.blueship581.hedwig.vendor.client.VendorClient;
import com.blueship581.hedwig.vendor.client.VendorClientFactory;
import com.blueship581.hedwig.vendor.model.VendorGlucoseData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Adaptive-window glucose polling scheduler.
 *
 * <p>A fast 2-second clock tick drives all connections, but actual vendor API requests are gated by
 * {@link PollingStateManager} which implements a multi-phase adaptive window around the predicted
 * next data arrival time.
 *
 * <p>Maintenance tasks (token expiry checks, subject sync) run on a separate low-frequency schedule
 * to avoid polluting the polling loop.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GlucosePollingScheduler {

  private final VendorConnectionMapper connectionMapper;
  private final MonitoredSubjectMapper subjectMapper;
  private final GlucoseService glucoseService;
  private final NightscoutSyncService nightscoutSyncService;
  private final TokenExpiryMonitorService tokenExpiryMonitorService;
  private final VendorConnectionService vendorConnectionService;
  private final VendorClientFactory vendorClientFactory;
  private final PollingStateManager pollingState;

  private final java.util.concurrent.atomic.AtomicLong lastNightscoutSyncSec =
      new java.util.concurrent.atomic.AtomicLong(0);

  // ════════════════════════════════════════════════════════════════════════
  // Fast clock — 2s tick, actual requests gated by PollingStateManager
  // ════════════════════════════════════════════════════════════════════════

  @Scheduled(fixedDelay = 2000)
  public void tick() {
    List<VendorConnection> activeConnections =
        connectionMapper.selectList(
            Wrappers.lambdaQuery(VendorConnection.class)
                .ne(VendorConnection::getTokenStatus, TokenStatus.EXPIRED));

    if (Instant.now().getEpochSecond() % 60 < 2) {
      log.info("[tick] activeConnections={}", activeConnections.size());
    }

    for (VendorConnection connection : activeConnections) {
      long intervalMs =
          VendorConnectionService.effectivePollingIntervalSeconds(connection) * 1000L;
      if (!pollingState.shouldPollNow(connection.getId(), intervalMs)) {
        if (Instant.now().getEpochSecond() % 30 < 2) {
          log.info(
              "[tick] conn={} skipped, phase={}, health={}, interval={}s",
              connection.getId(),
              pollingState.getCurrentPhase(connection.getId()),
              pollingState.getHealth(connection.getId()),
              intervalMs / 1000);
        }
        continue;
      }

      log.debug(
          "Polling connection {} (phase={}, health={})",
          connection.getId(),
          pollingState.getCurrentPhase(connection.getId()),
          pollingState.getHealth(connection.getId()));

      pollConnection(connection);
    }

    // Push pending readings to Nightscout — throttled to once every 10 seconds
    // to avoid hammering the DB with queries when there's nothing to push.
    long nowSec = Instant.now().getEpochSecond();
    if (nowSec - lastNightscoutSyncSec.get() >= 10) {
      lastNightscoutSyncSec.set(nowSec);
      int synced = nightscoutSyncService.syncPendingReadings();
      if (synced > 0) {
        log.info("Nightscout sync: pushed {} readings", synced);
      }
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Slow maintenance — 10 min: token check + subject sync
  // ════════════════════════════════════════════════════════════════════════

  @Scheduled(initialDelay = 0, fixedDelay = 600_000)
  public void maintenance() {
    tokenExpiryMonitorService.checkAll();

    List<VendorConnection> active =
        connectionMapper.selectList(
            Wrappers.lambdaQuery(VendorConnection.class)
                .ne(VendorConnection::getTokenStatus, TokenStatus.EXPIRED));

    for (VendorConnection conn : active) {
      try {
        vendorConnectionService.syncSubjects(conn);
      } catch (VendorTokenInvalidException e) {
        markTokenExpired(conn, e);
      } catch (Exception e) {
        log.warn("Subject sync failed for connection {}: {}", conn.getId(), e.getMessage());
      }
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Polling logic
  // ════════════════════════════════════════════════════════════════════════

  private void pollConnection(VendorConnection connection) {
    List<MonitoredSubject> subjects =
        subjectMapper.selectList(
            Wrappers.lambdaQuery(MonitoredSubject.class)
                .eq(MonitoredSubject::getVendorConnectionId, connection.getId())
                .eq(MonitoredSubject::getIsActive, true));
    if (subjects.isEmpty()) {
      return;
    }

    VendorClient client = vendorClientFactory.getClient(connection.getVendorType());

    try {
      if (client instanceof OttaiClient ottaiClient) {
        pollViaRelatives(ottaiClient, connection, subjects);
      } else if (client instanceof SiSensingClient siSensingClient) {
        pollViaFollowList(siSensingClient, connection, subjects);
      } else {
        pollPerSubject(connection, subjects);
      }
    } catch (VendorTokenInvalidException e) {
      markTokenExpired(connection, e);
    }
  }

  /** 令牌失效时停止继续轮询，等待用户重新连接（或 maintenance 任务的自动恢复）。 */
  private void markTokenExpired(VendorConnection connection, VendorTokenInvalidException e) {
    log.warn(
        "Vendor token invalid for connection {} ({}): {}",
        connection.getId(),
        connection.getVendorType(),
        e.getMessage());
    pollingState.remove(connection.getId());
    if (connection.getTokenStatus() != TokenStatus.EXPIRED) {
      connection.setTokenStatus(TokenStatus.EXPIRED);
      connectionMapper.updateById(connection);
    }
  }

  /**
   * Ottai path: one lightweight /relatives call returns realtime glucose for ALL subjects (with
   * receiveTime + arrowType).
   */
  private void pollViaRelatives(
      OttaiClient ottaiClient, VendorConnection connection, List<MonitoredSubject> subjects) {
    try {
      Map<String, VendorGlucoseData> realtimeData =
          ottaiClient.getRealtimeFromRelatives(
              connection.getAccessToken(), connection.getVendorUserId());

      boolean hasNew = false;
      Instant latestReceiveTime = null;

      for (MonitoredSubject subject : subjects) {
        VendorGlucoseData data = realtimeData.get(subject.getVendorSubjectId());
        if (data == null) {
          continue;
        }
        try {
          if (glucoseService.saveIfNewReading(subject.getId(), data)) {
            hasNew = true;
            log.info(
                "New glucose via relatives: subject={}, glucose={}, time={}",
                subject.getDisplayName(),
                data.getGlucoseMmol(),
                data.getReadingTime());
            if (latestReceiveTime == null || data.getReadingTime().isAfter(latestReceiveTime)) {
              latestReceiveTime = data.getReadingTime();
            }
          }
        } catch (Exception e) {
          log.error("Failed to save glucose for subject {}: {}", subject.getId(), e.getMessage());
        }
      }

      if (hasNew && latestReceiveTime != null) {
        pollingState.onDataReceived(connection.getId(), latestReceiveTime);
        connection.setLastSyncedAt(Instant.now());
        connectionMapper.updateById(connection);
      } else {
        pollingState.onPollSuccessNoNewData(connection.getId());
      }

    } catch (Exception e) {
      log.error(
          "Failed to poll via relatives for connection {}: {}", connection.getId(), e.getMessage());
      pollingState.onError(connection.getId());
    }
  }

  /**
   * SiSensing path: one lightweight /follow/list call returns realtime glucose for ALL subjects via
   * followedDeviceGlucoseDataPO.
   */
  private void pollViaFollowList(
      SiSensingClient siSensingClient,
      VendorConnection connection,
      List<MonitoredSubject> subjects) {
    try {
      Map<String, VendorGlucoseData> realtimeData =
          siSensingClient.getRealtimeFromFollowList(connection.getAccessToken());

      boolean hasNew = false;
      Instant latestReceiveTime = null;

      for (MonitoredSubject subject : subjects) {
        VendorGlucoseData data = realtimeData.get(subject.getVendorSubjectId());
        if (data == null) {
          log.info(
              "[SiSensing poll] subject={} not found in follow list response",
              subject.getDisplayName());
          continue;
        }
        log.info(
            "[SiSensing poll] subject={}, glucose={}, time={}, phase={}, health={}",
            subject.getDisplayName(),
            data.getGlucoseMmol(),
            data.getReadingTime(),
            pollingState.getCurrentPhase(connection.getId()),
            pollingState.getHealth(connection.getId()));
        try {
          if (glucoseService.saveIfNewReading(subject.getId(), data)) {
            hasNew = true;
            log.info(
                "New glucose via follow list: subject={}, glucose={}, time={}",
                subject.getDisplayName(),
                data.getGlucoseMmol(),
                data.getReadingTime());
            if (latestReceiveTime == null || data.getReadingTime().isAfter(latestReceiveTime)) {
              latestReceiveTime = data.getReadingTime();
            }
          }
        } catch (Exception e) {
          log.error("Failed to save glucose for subject {}: {}", subject.getId(), e.getMessage());
        }
      }

      if (hasNew && latestReceiveTime != null) {
        pollingState.onDataReceived(connection.getId(), latestReceiveTime);
        connection.setLastSyncedAt(Instant.now());
        connectionMapper.updateById(connection);
      } else {
        pollingState.onPollSuccessNoNewData(connection.getId());
      }

    } catch (Exception e) {
      log.error(
          "Failed to poll via follow list for connection {}: {}",
          connection.getId(),
          e.getMessage());
      pollingState.onError(connection.getId());
    }
  }

  /** Fallback for non-Ottai vendors: per-subject polling via heavy endpoint. */
  private void pollPerSubject(VendorConnection connection, List<MonitoredSubject> subjects) {
    boolean hasNew = false;

    for (MonitoredSubject subject : subjects) {
      try {
        if (glucoseService.pollLatest(connection.getId(), subject.getId())) {
          hasNew = true;
        }
      } catch (VendorTokenInvalidException e) {
        throw e;
      } catch (Exception e) {
        log.error(
            "Failed to poll glucose for subject {} (connection {}): {}",
            subject.getId(),
            connection.getId(),
            e.getMessage());
      }
    }
    if (hasNew) {
      pollingState.onDataReceived(connection.getId(), Instant.now());
    }
  }
}
