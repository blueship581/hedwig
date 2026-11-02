package com.blueship581.hedwig.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 轮询窗口的自适应偏移量必须夹紧，否则「越晚发现 → 窗口越往后 → 更晚发现」的正反馈会把
 * 微泰 1 分钟数据稳定拖到几十秒后才入库。
 */
class PollingOffsetClampTest {

  @Test
  void lateOffsetIsClampedToTenSeconds() {
    assertEquals(10_000, PollingStateManager.clampOffsetMs(30_000));
    assertEquals(10_000, PollingStateManager.clampOffsetMs(10_001));
  }

  @Test
  void earlyOffsetIsClampedToTwoSeconds() {
    assertEquals(-2_000, PollingStateManager.clampOffsetMs(-30_000));
    assertEquals(-2_000, PollingStateManager.clampOffsetMs(-2_001));
  }

  @Test
  void smallOffsetIsKeptAsIs() {
    assertEquals(0, PollingStateManager.clampOffsetMs(0));
    assertEquals(3_500, PollingStateManager.clampOffsetMs(3_500));
    assertEquals(-1_200, PollingStateManager.clampOffsetMs(-1_200));
  }
}
