package com.blueship581.hedwig.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blueship581.hedwig.domain.entity.GlucoseReading;
import com.blueship581.hedwig.domain.entity.MonitoredSubject;
import com.blueship581.hedwig.domain.entity.VendorConnection;
import com.blueship581.hedwig.domain.enums.TrendDirection;
import com.blueship581.hedwig.domain.mapper.GlucoseReadingMapper;
import com.blueship581.hedwig.domain.mapper.MonitoredSubjectMapper;
import com.blueship581.hedwig.domain.mapper.VendorConnectionMapper;
import com.blueship581.hedwig.dto.GlucoseReadingDto;
import com.blueship581.hedwig.dto.SyncResultDto;
import com.blueship581.hedwig.exception.ResourceNotFoundException;
import com.blueship581.hedwig.util.GlucoseConverter;
import com.blueship581.hedwig.util.GlucoseTrendCalculator;
import com.blueship581.hedwig.vendor.client.VendorClient;
import com.blueship581.hedwig.vendor.client.VendorClientFactory;
import com.blueship581.hedwig.vendor.model.VendorGlucoseData;
import com.blueship581.hedwig.vendor.model.VendorSubject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class GlucoseService {

  /** 取最新读数时多取几条，保证能找到 5 分钟前的参考点（微泰 1 分钟一条，5 分钟只需 6 条）。 */
  private static final int TREND_LOOKBACK_ROWS = 12;

  private final GlucoseReadingMapper readingMapper;
  private final MonitoredSubjectMapper subjectMapper;
  private final VendorConnectionMapper connectionMapper;
  private final VendorClientFactory vendorClientFactory;
  private final GlucoseConverter glucoseConverter;
  private final LatestSubjectDataCache latestSubjectDataCache;

  @Transactional
  public GlucoseReadingDto fetchLatest(Long connectionId, Long subjectId) {
    VendorConnection connection =
        Optional.ofNullable(connectionMapper.selectById(connectionId))
            .orElseThrow(() -> new ResourceNotFoundException("设备连接不存在：" + connectionId));
    MonitoredSubject subject =
        Optional.ofNullable(subjectMapper.selectById(subjectId))
            .orElseThrow(() -> new ResourceNotFoundException("监测对象不存在：" + subjectId));

    VendorClient client = vendorClientFactory.getClient(connection.getVendorType());
    VendorSubject vendorSubject = toVendorSubject(subject);

    VendorGlucoseData latest =
        client.getLatestGlucose(
            connection.getAccessToken(), connection.getVendorUserId(), vendorSubject);

    saveIfNew(subject.getId(), latest);
    return getLatestReading(subjectId);
  }

  @Transactional
  public List<GlucoseReadingDto> fetchHistory(Long connectionId, Long subjectId) {
    VendorConnection connection =
        Optional.ofNullable(connectionMapper.selectById(connectionId))
            .orElseThrow(() -> new ResourceNotFoundException("设备连接不存在：" + connectionId));
    MonitoredSubject subject =
        Optional.ofNullable(subjectMapper.selectById(subjectId))
            .orElseThrow(() -> new ResourceNotFoundException("监测对象不存在：" + subjectId));

    VendorClient client = vendorClientFactory.getClient(connection.getVendorType());
    VendorSubject vendorSubject = toVendorSubject(subject);

    List<VendorGlucoseData> history =
        client.getHistoricalGlucose(
            connection.getAccessToken(), connection.getVendorUserId(), vendorSubject);

    latestSubjectDataCache.evict(subjectId);
    int saved = 0;
    for (VendorGlucoseData data : history) {
      saveIfNew(subject.getId(), data, false);
      saved++;
    }
    log.debug("Fetched {} historical readings for subject {}, deduped into DB", saved, subjectId);

    refreshLatestDataCache(subjectId);
    return getReadings(subjectId);
  }

  public List<GlucoseReadingDto> getReadings(Long subjectId) {
    List<GlucoseReading> storedReadings =
        readingMapper.selectList(
            Wrappers.lambdaQuery(GlucoseReading.class)
                .eq(GlucoseReading::getMonitoredSubjectId, subjectId)
                .orderByDesc(GlucoseReading::getReadingTime));

    List<GlucoseReading> readings =
        storedReadings.isEmpty() ? fetchHistoryEntitiesForSubject(subjectId) : storedReadings;

    int[] references = referenceIndexes(readings);
    return IntStream.range(0, readings.size())
        .mapToObj(
            index -> {
              int ref = references[index];
              return toDto(readings.get(index), ref >= 0 ? readings.get(ref) : null);
            })
        .collect(Collectors.toList());
  }

  public GlucoseReadingDto getLatestReading(Long subjectId) {
    Optional<GlucoseReadingDto> cached =
        latestSubjectDataCache.get(subjectId).map(LatestSubjectData::getLatestReading);
    if (cached.isPresent()) {
      return cached.get();
    }

    List<GlucoseReading> latestReadings = loadLatestReadings(subjectId, TREND_LOOKBACK_ROWS);
    if (latestReadings.isEmpty()) {
      fetchHistoryEntitiesForSubject(subjectId);
      latestReadings = loadLatestReadings(subjectId, TREND_LOOKBACK_ROWS);
    }

    if (latestReadings.isEmpty()) {
      throw new ResourceNotFoundException("未找到监测对象的血糖读数：" + subjectId);
    }

    return cacheLatestReading(subjectId, latestReadings);
  }

  /**
   * Save a glucose reading obtained externally (e.g. from the Ottai relatives endpoint) if it
   * doesn't already exist. If the same readingTime already exists, refresh the stored payload so
   * corrected trend/value data can replace stale vendor data. Returns true only when a brand new
   * row was persisted.
   */
  @Transactional
  public boolean saveIfNewReading(Long subjectId, VendorGlucoseData data) {
    return saveIfNew(subjectId, data, true);
  }

  @Transactional
  public SyncResultDto syncHistory(Long subjectId) {
    MonitoredSubject subject =
        Optional.ofNullable(subjectMapper.selectById(subjectId))
            .orElseThrow(() -> new ResourceNotFoundException("监测对象不存在：" + subjectId));
    VendorConnection connection =
        Optional.ofNullable(connectionMapper.selectById(subject.getVendorConnectionId()))
            .orElseThrow(
                () -> new ResourceNotFoundException("设备连接不存在：" + subject.getVendorConnectionId()));

    VendorClient client = vendorClientFactory.getClient(connection.getVendorType());
    VendorSubject vendorSubject = toVendorSubject(subject);

    List<VendorGlucoseData> history =
        client.getHistoricalGlucose(
            connection.getAccessToken(), connection.getVendorUserId(), vendorSubject);

    if (history.isEmpty()) {
      return SyncResultDto.builder().syncedCount(0).build();
    }

    Instant minTime =
        history.stream()
            .map(VendorGlucoseData::getReadingTime)
            .min(Instant::compareTo)
            .orElseThrow();
    Instant maxTime =
        history.stream()
            .map(VendorGlucoseData::getReadingTime)
            .max(Instant::compareTo)
            .orElseThrow();

    readingMapper.delete(
        Wrappers.lambdaQuery(GlucoseReading.class)
            .eq(GlucoseReading::getMonitoredSubjectId, subjectId)
            .between(GlucoseReading::getReadingTime, minTime, maxTime));

    latestSubjectDataCache.evict(subjectId);
    for (VendorGlucoseData data : history) {
      saveIfNew(subjectId, data, false);
    }
    refreshLatestDataCache(subjectId);

    connection.setLastSyncedAt(Instant.now());
    connectionMapper.updateById(connection);

    log.info(
        "Synced {} readings for subject {} (range {} ~ {})",
        history.size(),
        subjectId,
        minTime,
        maxTime);

    return SyncResultDto.builder()
        .syncedCount(history.size())
        .timeRangeStart(minTime)
        .timeRangeEnd(maxTime)
        .build();
  }

  /**
   * Poll the latest glucose from vendor and return whether new data was saved. Used by the
   * scheduler to decide polling frequency.
   */
  @Transactional
  public boolean pollLatest(Long connectionId, Long subjectId) {
    VendorConnection connection =
        Optional.ofNullable(connectionMapper.selectById(connectionId))
            .orElseThrow(() -> new ResourceNotFoundException("设备连接不存在：" + connectionId));
    MonitoredSubject subject =
        Optional.ofNullable(subjectMapper.selectById(subjectId))
            .orElseThrow(() -> new ResourceNotFoundException("监测对象不存在：" + subjectId));

    VendorClient client = vendorClientFactory.getClient(connection.getVendorType());
    VendorSubject vendorSubject = toVendorSubject(subject);

    VendorGlucoseData latest =
        client.getLatestGlucose(
            connection.getAccessToken(), connection.getVendorUserId(), vendorSubject);

    boolean isNew = saveIfNew(subject.getId(), latest, true);
    if (isNew) {
      connection.setLastSyncedAt(Instant.now());
      connectionMapper.updateById(connection);
    }
    return isNew;
  }

  private boolean saveIfNew(Long subjectId, VendorGlucoseData data) {
    return saveIfNew(subjectId, data, true);
  }

  private boolean saveIfNew(Long subjectId, VendorGlucoseData data, boolean refreshCache) {
    double mgdl = glucoseConverter.mmolToMgdl(data.getGlucoseMmol());
    TrendDirection trendDirection =
        data.getTrendDirection() == null ? TrendDirection.NONE : data.getTrendDirection();
    GlucoseReading existing =
        readingMapper
            .selectList(
                Wrappers.lambdaQuery(GlucoseReading.class)
                    .eq(GlucoseReading::getMonitoredSubjectId, subjectId)
                    .eq(GlucoseReading::getReadingTime, data.getReadingTime())
                    .last("LIMIT 1"))
            .stream()
            .findFirst()
            .orElse(null);
    if (existing != null) {
      boolean changed =
          !Objects.equals(existing.getGlucoseMmol(), data.getGlucoseMmol())
              || !Objects.equals(existing.getGlucoseMgdl(), mgdl)
              || existing.getTrendDirection() != trendDirection;
      if (!changed) {
        return false;
      }

      existing.setGlucoseMmol(data.getGlucoseMmol());
      existing.setGlucoseMgdl(mgdl);
      existing.setTrendDirection(trendDirection);
      existing.setPushedToNightscout(false);
      readingMapper.updateById(existing);
      if (refreshCache) {
        refreshLatestDataCache(subjectId);
      }
      return false;
    }

    GlucoseReading reading =
        GlucoseReading.builder()
            .monitoredSubjectId(subjectId)
            .glucoseMmol(data.getGlucoseMmol())
            .glucoseMgdl(mgdl)
            .trendDirection(trendDirection)
            .readingTime(data.getReadingTime())
            .pushedToNightscout(false)
            .build();
    readingMapper.insert(reading);
    if (refreshCache) {
      refreshLatestDataCache(subjectId);
    }
    return true;
  }

  private List<GlucoseReading> fetchHistoryEntitiesForSubject(Long subjectId) {
    MonitoredSubject subject =
        Optional.ofNullable(subjectMapper.selectById(subjectId))
            .orElseThrow(() -> new ResourceNotFoundException("监测对象不存在：" + subjectId));
    VendorConnection connection =
        Optional.ofNullable(connectionMapper.selectById(subject.getVendorConnectionId()))
            .orElseThrow(
                () -> new ResourceNotFoundException("设备连接不存在：" + subject.getVendorConnectionId()));

    VendorClient client = vendorClientFactory.getClient(connection.getVendorType());
    VendorSubject vendorSubject = toVendorSubject(subject);
    List<VendorGlucoseData> history =
        client.getHistoricalGlucose(
            connection.getAccessToken(), connection.getVendorUserId(), vendorSubject);

    latestSubjectDataCache.evict(subjectId);
    for (VendorGlucoseData data : history) {
      saveIfNew(subjectId, data, false);
    }

    connection.setLastSyncedAt(Instant.now());
    connectionMapper.updateById(connection);

    refreshLatestDataCache(subjectId);
    return loadLatestReadings(subjectId, Integer.MAX_VALUE);
  }

  private VendorSubject toVendorSubject(MonitoredSubject s) {
    return VendorSubject.builder()
        .subjectId(s.getVendorSubjectId())
        .deviceId(s.getVendorDeviceId())
        .displayName(s.getDisplayName())
        .build();
  }


  /** 参考点之间的时间跨度上限：超过 15 分钟的缺口，箭头没有意义。 */
  private static final long MAX_TREND_GAP_SEC = 900;

  /**
   * 用「已取整的 5 分钟 delta」判定箭头，保证界面上的数值与箭头一致；
   * 参考点过于久远（>15 分钟缺口）时返回 NONE。
   */
  private static TrendDirection trendFor(
      GlucoseReading current, GlucoseReading reference, Double delta) {
    if (delta == null || reference == null) {
      return TrendDirection.NONE;
    }
    Instant currentTime = current.getReadingTime();
    Instant referenceTime = reference.getReadingTime();
    if (currentTime == null || referenceTime == null) {
      return GlucoseTrendCalculator.classify(delta);
    }
    long gap = Math.abs(currentTime.getEpochSecond() - referenceTime.getEpochSecond());
    if (gap > MAX_TREND_GAP_SEC) {
      return TrendDirection.NONE;
    }
    return GlucoseTrendCalculator.classify(delta);
  }

  /**
   * 为每条读数（按时间倒序）找到约 {@link GlucoseTrendCalculator#DEFAULT_TREND_WINDOW_SECONDS} 秒前的
   * 参考读数下标，返回 -1 表示历史不足一个窗口。时间轴单调，用双指针一次扫描完成。
   */
  static int[] referenceIndexes(List<GlucoseReading> readingsDesc) {
    int n = readingsDesc.size();
    int[] refs = new int[n];
    int j = 1;
    for (int i = 0; i < n; i++) {
      Instant current = readingsDesc.get(i).getReadingTime();
      if (current == null) {
        refs[i] = i + 1 < n ? i + 1 : -1;
        continue;
      }
      Instant target = current.minusSeconds(GlucoseTrendCalculator.DEFAULT_TREND_WINDOW_SECONDS);
      if (j <= i) {
        j = i + 1;
      }
      while (j < n) {
        Instant candidate = readingsDesc.get(j).getReadingTime();
        if (candidate == null) {
          j++;
          continue;
        }
        if (!candidate.isAfter(target)) {
          break;
        }
        j++;
      }
      refs[i] = j < n ? j : -1;
    }
    return refs;
  }

  private List<GlucoseReading> loadLatestReadings(Long subjectId, int limit) {
    if (limit == Integer.MAX_VALUE) {
      return readingMapper.selectList(
          Wrappers.lambdaQuery(GlucoseReading.class)
              .eq(GlucoseReading::getMonitoredSubjectId, subjectId)
              .orderByDesc(GlucoseReading::getReadingTime));
    }
    return readingMapper.selectList(
        Wrappers.lambdaQuery(GlucoseReading.class)
            .eq(GlucoseReading::getMonitoredSubjectId, subjectId)
            .orderByDesc(GlucoseReading::getReadingTime)
            .last("LIMIT " + limit));
  }

  private void refreshLatestDataCache(Long subjectId) {
    List<GlucoseReading> latestReadings = loadLatestReadings(subjectId, TREND_LOOKBACK_ROWS);
    if (latestReadings.isEmpty()) {
      latestSubjectDataCache.evict(subjectId);
      return;
    }
    cacheLatestReading(subjectId, latestReadings);
  }

  private GlucoseReadingDto cacheLatestReading(
      Long subjectId, List<GlucoseReading> latestReadings) {
    GlucoseReading latest = latestReadings.get(0);
    int refIndex = referenceIndexes(latestReadings)[0];
    GlucoseReading reference = refIndex >= 0 ? latestReadings.get(refIndex) : null;
    GlucoseReadingDto latestDto = toDto(latest, reference);
    latestSubjectDataCache.put(
        LatestSubjectData.builder()
            .subjectId(subjectId)
            .latestReading(latestDto)
            .updatedAt(Instant.now())
            .build());
    return latestDto;
  }

  /**
   * 趋势与 delta 都相对「约 5 分钟前的读数」，与 Nightscout 的箭头语义一致。
   *
   * <p>之前 delta/趋势取的是上一条（微泰 1 分钟一条），再按 5 分钟归一化，等于把 1 分钟的波动放大 5 倍：
   * 下降 0.2 mmol 会被算成 -1.0 / 5min，因而显示成 ↓↓。参考读数改为 5 分钟窗口后，
   * 同样的 -0.2/5min 会正确落到「→ 平稳」。
   */
  private GlucoseReadingDto toDto(GlucoseReading current, GlucoseReading reference) {
    Double refMmol = reference == null ? null : reference.getGlucoseMmol();
    Double delta =
        (refMmol != null && current.getGlucoseMmol() != null)
            ? Math.round((current.getGlucoseMmol() - refMmol) * 10.0) / 10.0
            : null;
    return GlucoseReadingDto.builder()
        .id(current.getId())
        .monitoredSubjectId(current.getMonitoredSubjectId())
        .glucoseMmol(current.getGlucoseMmol())
        .glucoseMgdl(current.getGlucoseMgdl())
        .trendDirection(trendFor(current, reference, delta))
        .delta(delta)
        .readingTime(current.getReadingTime())
        .pushedToNightscout(current.getPushedToNightscout())
        .build();
  }
}
