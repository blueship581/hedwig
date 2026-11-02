package com.blueship581.hedwig.util;

import com.blueship581.hedwig.domain.enums.TrendDirection;

import java.time.Instant;
import java.util.List;

public final class GlucoseTrendCalculator {

  /**
   * Trend 参考窗口（秒）。血糖趋势以「5 分钟内的变化速率」为准：5 分钟一个点的厂商（欧泰/硅基）天然满足，
   * 1 分钟一个点的厂商（微泰）需要回溯到约 5 分钟前的读数，否则单点抖动会被放大成箭头乱跳。
   */
  public static final long DEFAULT_TREND_WINDOW_SECONDS = 300;

  /**
   * Expected interval between two consecutive CGM readings in seconds. Most CGM sensors produce a
   * reading every 5 minutes (300s).
   */
  private static final double EXPECTED_INTERVAL_SEC = 300.0;

  /**
   * Maximum interval (in seconds) beyond which trend calculation is not meaningful. If two readings
   * are more than 30 minutes apart, the rate-of-change is unreliable.
   */
  private static final long MAX_INTERVAL_SEC = 1800;


  private GlucoseTrendCalculator() {}

  /**
   * Calculate trend direction from two consecutive readings, normalizing by time interval. Falls
   * back to simple delta when timestamps are unavailable.
   *
   * @param currentMmol current glucose value in mmol/L
   * @param previousMmol previous glucose value in mmol/L
   * @param currentTime timestamp of current reading (nullable)
   * @param previousTime timestamp of previous reading (nullable)
   */
  public static TrendDirection calculate(
      Double currentMmol, Double previousMmol, Instant currentTime, Instant previousTime) {
    if (currentMmol == null || previousMmol == null) {
      return TrendDirection.NONE;
    }

    double rawDelta = currentMmol - previousMmol;

    // Normalize delta to a per-5-minute rate if timestamps are available
    double normalizedDelta;
    if (currentTime != null && previousTime != null) {
      long intervalSec = Math.abs(currentTime.getEpochSecond() - previousTime.getEpochSecond());
      if (intervalSec <= 0) {
        return TrendDirection.FLAT;
      }
      if (intervalSec > MAX_INTERVAL_SEC) {
        // Gap too large for meaningful trend — report NONE
        return TrendDirection.NONE;
      }
      // Scale delta to what it would be over one standard 5-minute interval
      normalizedDelta = rawDelta * (EXPECTED_INTERVAL_SEC / intervalSec);
    } else {
      // No timestamps — assume standard 5-minute interval (backward compatible)
      normalizedDelta = rawDelta;
    }

    return classifyDelta(normalizedDelta);
  }

  /**
   * Backward-compatible overload: calculate without timestamps. Assumes the two readings are
   * exactly one standard interval apart.
   */
  public static TrendDirection calculate(Double currentMmol, Double previousMmol) {
    return calculate(currentMmol, previousMmol, null, null);
  }

  private static TrendDirection classifyDelta(double delta) {
    double absDelta = Math.abs(delta);

    if (absDelta < 0.3) {
      return TrendDirection.FLAT;
    }

    if (delta > 0) {
      if (absDelta >= 0.9) {
        return TrendDirection.DOUBLE_UP;
      }
      if (absDelta >= 0.6) {
        return TrendDirection.SINGLE_UP;
      }
      return TrendDirection.FORTY_FIVE_UP;
    }

    if (absDelta >= 0.9) {
      return TrendDirection.DOUBLE_DOWN;
    }
    if (absDelta >= 0.6) {
      return TrendDirection.SINGLE_DOWN;
    }
    return TrendDirection.FORTY_FIVE_DOWN;
  }

  /**
   * 在「按时间倒序」排列的读数里，为下标 {@code index} 的读数挑选趋势参考读数的下标。
   *
   * <p>目标是找到「不晚于 index 读数时间 − windowSeconds」的最新一条读数，等价于 5 分钟窗口的起点。
   * 对 5 分钟一个点的厂商，这正好是上一条读数；对 1 分钟一个点的微泰，则跳过中间 4 条，避免箭头抖动。
   *
   * @return 参考读数下标；返回 -1 表示历史不足一个窗口，无法计算趋势
   */
  public static int referenceIndex(List<Instant> readingTimesDesc, int index, long windowSeconds) {
    if (readingTimesDesc == null || index < 0 || index >= readingTimesDesc.size()) {
      return -1;
    }
    Instant current = readingTimesDesc.get(index);
    if (current == null) {
      // 没有时间信息时退回「上一条」，保持旧行为
      return index + 1 < readingTimesDesc.size() ? index + 1 : -1;
    }
    Instant target = current.minusSeconds(windowSeconds);
    for (int i = index + 1; i < readingTimesDesc.size(); i++) {
      Instant candidate = readingTimesDesc.get(i);
      if (candidate == null) {
        continue;
      }
      if (!candidate.isAfter(target)) {
        return i;
      }
    }
    return -1;
  }
}
