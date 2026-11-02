package com.blueship581.hedwig.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blueship581.hedwig.domain.entity.GlucoseReading;
import com.blueship581.hedwig.domain.entity.MonitoredSubject;
import com.blueship581.hedwig.domain.entity.NightscoutTarget;
import com.blueship581.hedwig.domain.entity.VendorConnection;
import com.blueship581.hedwig.domain.enums.TrendDirection;
import com.blueship581.hedwig.domain.mapper.GlucoseReadingMapper;
import com.blueship581.hedwig.domain.mapper.MonitoredSubjectMapper;
import com.blueship581.hedwig.domain.mapper.VendorConnectionMapper;
import com.blueship581.hedwig.util.GlucoseTrendCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NightscoutSyncService {

  private final GlucoseReadingMapper readingMapper;
  private final MonitoredSubjectMapper subjectMapper;
  private final VendorConnectionMapper connectionMapper;
  private final NightscoutClient nightscoutClient;
  private final NightscoutTargetService targetService;

  /**
   * 同步待推送的血糖数据到所有 ACTIVE 的 Nightscout 目标。 按用户维度隔离数据：每个 target 只能推送其所属用户的监测对象数据。 只有当一条 reading
   * 被其所有相关 target 都推送成功后，才标记为已推送。 返回总推送条数。
   */
  @Transactional
  public int syncPendingReadings() {
    List<NightscoutTarget> activeTargets = targetService.getAllActiveTargets();
    if (activeTargets.isEmpty()) {
      return 0;
    }

    List<GlucoseReading> allPending =
        readingMapper.selectList(
            Wrappers.lambdaQuery(GlucoseReading.class)
                .eq(GlucoseReading::getPushedToNightscout, false));
    if (allPending.isEmpty()) {
      return 0;
    }

    // Build subject → ownerUserId lookup for user-level isolation
    Set<Long> allSubjectIds =
        allPending.stream().map(GlucoseReading::getMonitoredSubjectId).collect(Collectors.toSet());
    Map<Long, Long> subjectToOwner = resolveSubjectOwners(allSubjectIds);

    // Pre-compute trends for all pending readings
    Map<Long, TrendDirection> derivedTrends = deriveNightscoutTrends(allPending);

    // Track which readings were successfully pushed to each target
    // Key = readingId, Value = set of target IDs that succeeded
    Map<Long, Set<Long>> readingSuccessTargets = new HashMap<>();
    // Track all target IDs relevant to each reading
    Map<Long, Set<Long>> readingRelevantTargets = new HashMap<>();

    int totalPushed = 0;

    for (NightscoutTarget target : activeTargets) {
      Long targetOwnerId = target.getGatewayUserId();

      // Filter pending readings to only those belonging to this target's owner
      List<GlucoseReading> ownerReadings =
          allPending.stream()
              .filter(r -> targetOwnerId.equals(subjectToOwner.get(r.getMonitoredSubjectId())))
              .collect(Collectors.toList());

      // Further filter by monitoredSubjectId if the target is bound to a specific subject
      List<GlucoseReading> targetReadings;
      if (target.getMonitoredSubjectId() != null) {
        targetReadings =
            ownerReadings.stream()
                .filter(r -> r.getMonitoredSubjectId().equals(target.getMonitoredSubjectId()))
                .collect(Collectors.toList());
      } else {
        targetReadings = ownerReadings;
      }

      if (targetReadings.isEmpty()) {
        continue;
      }

      // Record relevance: these readings are relevant to this target
      for (GlucoseReading r : targetReadings) {
        readingRelevantTargets.computeIfAbsent(r.getId(), k -> new HashSet<>()).add(target.getId());
      }

      List<NightscoutClient.SgvEntry> targetEntries =
          targetReadings.stream()
              .map(
                  r ->
                      nightscoutClient.toSgvEntryFromMgdl(
                          r.getGlucoseMgdl(),
                          r.getReadingTime(),
                          trendToNightscout(
                              derivedTrends.getOrDefault(r.getId(), TrendDirection.NONE)),
                          "hedwig"))
              .collect(Collectors.toList());

      try {
        int pushed = nightscoutClient.pushEntries(target, targetEntries);
        if (pushed > 0) {
          targetService.updatePushStatus(target.getId(), true, null);
          totalPushed += pushed;
          // Record success for each reading pushed to this target
          for (GlucoseReading r : targetReadings) {
            readingSuccessTargets
                .computeIfAbsent(r.getId(), k -> new HashSet<>())
                .add(target.getId());
          }
        }
      } catch (Exception e) {
        log.error("Failed to push to Nightscout target '{}': {}", target.getName(), e.getMessage());
        targetService.updatePushStatus(target.getId(), false, e.getMessage());
      }
    }

    // Only mark a reading as pushed if ALL its relevant targets succeeded
    int markedCount = 0;
    for (GlucoseReading r : allPending) {
      Set<Long> relevant = readingRelevantTargets.get(r.getId());
      Set<Long> succeeded = readingSuccessTargets.get(r.getId());
      if (relevant != null
          && !relevant.isEmpty()
          && succeeded != null
          && succeeded.containsAll(relevant)) {
        r.setPushedToNightscout(true);
        readingMapper.updateById(r);
        markedCount++;
      }
    }

    if (markedCount > 0) {
      log.info(
          "Marked {} readings as pushed to Nightscout ({} targets)",
          markedCount,
          activeTargets.size());
    }

    return totalPushed;
  }

  /**
   * Resolve subject IDs to their owning gateway user IDs. subject → vendorConnection →
   * gatewayUserId
   */
  private Map<Long, Long> resolveSubjectOwners(Set<Long> subjectIds) {
    if (subjectIds.isEmpty()) {
      return Map.of();
    }
    List<MonitoredSubject> subjects =
        subjectMapper.selectList(
            Wrappers.lambdaQuery(MonitoredSubject.class).in(MonitoredSubject::getId, subjectIds));

    Set<Long> connectionIds =
        subjects.stream()
            .map(MonitoredSubject::getVendorConnectionId)
            .filter(id -> id != null)
            .collect(Collectors.toSet());

    Map<Long, Long> connectionToUser = new HashMap<>();
    if (!connectionIds.isEmpty()) {
      List<VendorConnection> connections =
          connectionMapper.selectList(
              Wrappers.lambdaQuery(VendorConnection.class)
                  .in(VendorConnection::getId, connectionIds));
      for (VendorConnection conn : connections) {
        connectionToUser.put(conn.getId(), conn.getGatewayUserId());
      }
    }

    Map<Long, Long> result = new HashMap<>();
    for (MonitoredSubject s : subjects) {
      if (s.getVendorConnectionId() != null) {
        Long ownerId = connectionToUser.get(s.getVendorConnectionId());
        if (ownerId != null) {
          result.put(s.getId(), ownerId);
        }
      }
    }
    return result;
  }

  private Map<Long, TrendDirection> deriveNightscoutTrends(List<GlucoseReading> pending) {
    Set<Long> subjectIds =
        pending.stream().map(GlucoseReading::getMonitoredSubjectId).collect(Collectors.toSet());
    if (subjectIds.isEmpty()) {
      return Map.of();
    }

    // Only load recent readings (last 2 hours) to avoid full-table scan
    Instant cutoff = Instant.now().minusSeconds(7200);
    List<GlucoseReading> subjectReadings =
        readingMapper.selectList(
            Wrappers.lambdaQuery(GlucoseReading.class)
                .in(GlucoseReading::getMonitoredSubjectId, subjectIds)
                .ge(GlucoseReading::getReadingTime, cutoff)
                .orderByDesc(GlucoseReading::getReadingTime));

    Map<Long, TrendDirection> trendByReadingId = new HashMap<>();
    Map<Long, List<GlucoseReading>> readingsBySubject =
        subjectReadings.stream()
            .collect(Collectors.groupingBy(GlucoseReading::getMonitoredSubjectId));

    readingsBySubject
        .values()
        .forEach(
            readings -> {
              List<Instant> times =
                  readings.stream().map(GlucoseReading::getReadingTime).collect(Collectors.toList());
              for (int index = 0; index < readings.size(); index++) {
                GlucoseReading current = readings.get(index);
                if (current.getId() == null) {
                  continue;
                }
                int referenceIndex =
                    GlucoseTrendCalculator.referenceIndex(
                        times, index, GlucoseTrendCalculator.DEFAULT_TREND_WINDOW_SECONDS);
                GlucoseReading reference =
                    referenceIndex >= 0 ? readings.get(referenceIndex) : null;
                trendByReadingId.put(
                    current.getId(),
                    GlucoseTrendCalculator.calculate(
                        current.getGlucoseMmol(),
                        reference == null ? null : reference.getGlucoseMmol(),
                        current.getReadingTime(),
                        reference == null ? null : reference.getReadingTime()));
              }
            });

    return trendByReadingId;
  }

  private String trendToNightscout(TrendDirection trendDirection) {
    return switch (trendDirection) {
      case DOUBLE_UP -> "DoubleUp";
      case SINGLE_UP -> "SingleUp";
      case FORTY_FIVE_UP -> "FortyFiveUp";
      case FLAT -> "Flat";
      case FORTY_FIVE_DOWN -> "FortyFiveDown";
      case SINGLE_DOWN -> "SingleDown";
      case DOUBLE_DOWN -> "DoubleDown";
      default -> "NOT COMPUTABLE";
    };
  }
}
