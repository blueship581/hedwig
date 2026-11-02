package com.blueship581.hedwig.service;

import com.blueship581.hedwig.domain.entity.GlucoseReading;
import com.blueship581.hedwig.util.GlucoseTrendCalculator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 趋势参考点必须落在「约 5 分钟前」，否则 1 分钟一条的微泰数据会被放大 5 倍，
 * 下降 0.2 mmol 就会显示成双箭头。
 */
class TrendReferenceIndexTest {

  private static List<GlucoseReading> series(int count, int stepSeconds) {
    List<GlucoseReading> list = new ArrayList<>();
    Instant base = Instant.parse("2026-11-02T10:00:00Z");
    for (int i = 0; i < count; i++) {
      list.add(
          GlucoseReading.builder()
              .readingTime(base.minusSeconds((long) i * stepSeconds))
              .glucoseMmol(9.0)
              .build());
    }
    return list;
  }

  @Test
  void oneMinuteCadenceLooksBackFiveRows() {
    int[] refs = GlucoseService.referenceIndexes(series(10, 60));
    assertEquals(5, refs[0]);
    assertEquals(6, refs[1]);
    assertEquals(-1, refs[7]); // 只剩 2 条，不足 5 分钟
  }

  @Test
  void fiveMinuteCadenceLooksBackOneRow() {
    int[] refs = GlucoseService.referenceIndexes(series(5, 300));
    assertEquals(1, refs[0]);
    assertEquals(2, refs[1]);
  }

  @Test
  void shortHistoryHasNoReference() {
    assertEquals(-1, GlucoseService.referenceIndexes(series(3, 60))[0]);
    assertEquals(0, GlucoseService.referenceIndexes(List.of()).length);
  }

  @Test
  void nullTimestampsFallBackToPreviousRow() {
    List<GlucoseReading> list = series(3, 60);
    list.get(0).setReadingTime(null);
    int[] refs = GlucoseService.referenceIndexes(list);
    assertEquals(1, refs[0]);
  }

  @Test
  void droppingPointTwoOverFiveMinutesStaysFlat() {
    // 5 分钟掉 0.2 -> 0.2，属于平稳区间
    assertEquals(
        "FLAT",
        GlucoseTrendCalculator.calculate(
                9.0, 9.2, Instant.parse("2026-11-02T10:00:00Z"), Instant.parse("2026-11-02T09:55:00Z"))
            .name());
  }
}
