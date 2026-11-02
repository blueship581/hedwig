package com.blueship581.hedwig.util;

import com.blueship581.hedwig.domain.enums.TrendDirection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlucoseTrendCalculatorTest {

    // ── Backward-compatible tests (no timestamps) ──

    @Test
    void returnsNoneWhenPreviousReadingMissing() {
        assertEquals(TrendDirection.NONE, GlucoseTrendCalculator.calculate(6.5, null));
    }

    @Test
    void mapsFlatRange() {
        assertEquals(TrendDirection.FLAT, GlucoseTrendCalculator.calculate(6.7, 6.5));
        assertEquals(TrendDirection.FLAT, GlucoseTrendCalculator.calculate(6.3, 6.5));
    }

    @Test
    void mapsFortyFiveRanges() {
        assertEquals(TrendDirection.FORTY_FIVE_UP, GlucoseTrendCalculator.calculate(6.9, 6.5));
        assertEquals(TrendDirection.FORTY_FIVE_DOWN, GlucoseTrendCalculator.calculate(6.1, 6.5));
    }

    @Test
    void mapsSingleRanges() {
        assertEquals(TrendDirection.SINGLE_UP, GlucoseTrendCalculator.calculate(7.2, 6.5));
        assertEquals(TrendDirection.SINGLE_DOWN, GlucoseTrendCalculator.calculate(5.8, 6.5));
    }

    @Test
    void mapsDoubleRanges() {
        assertEquals(TrendDirection.DOUBLE_UP, GlucoseTrendCalculator.calculate(7.4, 6.5));
        assertEquals(TrendDirection.DOUBLE_DOWN, GlucoseTrendCalculator.calculate(5.6, 6.5));
    }

    // ── Time-aware tests ──

    @Test
    void standardIntervalSameAsNoTimestamp() {
        // 5-minute interval: same result as no-timestamp call
        Instant now = Instant.now();
        Instant fiveMinAgo = now.minusSeconds(300);
        assertEquals(
                GlucoseTrendCalculator.calculate(6.9, 6.5),
                GlucoseTrendCalculator.calculate(6.9, 6.5, now, fiveMinAgo)
        );
    }

    @Test
    void longIntervalNormalizesToFlat() {
        // 0.4 delta over 10 minutes → normalized to 0.2 per 5 min → FLAT
        Instant now = Instant.now();
        Instant tenMinAgo = now.minusSeconds(600);
        assertEquals(TrendDirection.FLAT,
                GlucoseTrendCalculator.calculate(6.9, 6.5, now, tenMinAgo));
    }

    @Test
    void shortIntervalAmplifiesTrend() {
        // 0.4 delta over 2.5 minutes → normalized to 0.8 per 5 min → SINGLE_UP
        Instant now = Instant.now();
        Instant twoHalfMinAgo = now.minusSeconds(150);
        assertEquals(TrendDirection.SINGLE_UP,
                GlucoseTrendCalculator.calculate(6.9, 6.5, now, twoHalfMinAgo));
    }

    @Test
    void veryLargeGapReturnsNone() {
        // Gap > 30 minutes → NONE (unreliable)
        Instant now = Instant.now();
        Instant fortyMinAgo = now.minusSeconds(2400);
        assertEquals(TrendDirection.NONE,
                GlucoseTrendCalculator.calculate(7.5, 6.5, now, fortyMinAgo));
    }

    @Test
    void zeroIntervalReturnsFlat() {
        Instant now = Instant.now();
        assertEquals(TrendDirection.FLAT,
                GlucoseTrendCalculator.calculate(7.5, 6.5, now, now));
    }

    // ── Trend reference window (matters for 1-minute vendors such as MicroTech) ──

    @Test
    void referenceIndexUsesPreviousReadingForFiveMinuteCadence() {
        Instant t0 = Instant.parse("2026-03-23T12:00:00Z");
        List<Instant> times = List.of(t0, t0.minusSeconds(300), t0.minusSeconds(600));

        assertEquals(1, GlucoseTrendCalculator.referenceIndex(times, 0, 300));
    }

    @Test
    void referenceIndexSkipsIntermediateReadingsForOneMinuteCadence() {
        // 微泰：每分钟一个点，趋势必须回看 5 分钟而不是上一条，否则箭头会被单点抖动放大
        Instant t0 = Instant.parse("2026-03-23T12:00:00Z");
        List<Instant> times = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            times.add(t0.minusSeconds(60L * i));
        }

        assertEquals(5, GlucoseTrendCalculator.referenceIndex(times, 0, 300));
        assertEquals(6, GlucoseTrendCalculator.referenceIndex(times, 1, 300));
    }

    @Test
    void referenceIndexReturnsMinusOneWhenHistoryIsShorterThanWindow() {
        Instant t0 = Instant.parse("2026-03-23T12:00:00Z");
        List<Instant> times = List.of(t0, t0.minusSeconds(60), t0.minusSeconds(120));

        assertEquals(-1, GlucoseTrendCalculator.referenceIndex(times, 0, 300));
    }

    @Test
    void referenceIndexFallsBackToNextReadingWhenTimesAreUnknown() {
        List<Instant> times = new ArrayList<>();
        times.add(null);
        times.add(null);

        assertEquals(1, GlucoseTrendCalculator.referenceIndex(times, 0, 300));
        assertEquals(-1, GlucoseTrendCalculator.referenceIndex(times, 1, 300));
    }
}
