package com.blueship581.hedwig.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-connection adaptive polling state manager.
 *
 * <p>Phases relative to T_next (= lastReceiveTime + dataInterval + meanOffset):
 *
 * <pre>
 *  IDLE          EARLY_PROBE       CORE_WINDOW    LATE_CHASE    TIMEOUT_BACKOFF
 * |──────────────|─────────────────|──────────────|─────────────|───────────────→
 *             T-25s              T-5s           T+5s          T+30s
 *               8s/req            4s/req         6s/req       exponential
 * </pre>
 *
 * <p>上面是 5 分钟上报节奏（欧泰 / 硅基）的默认窗口。微泰 AiDEX X 每 1 分钟出一个点，若沿用 5 分钟窗口，
 * 会出现「整分钟都在 IDLE」的错位，因此各阶段阈值会按 {@code interval / 300s} 等比收缩，并设置下限，
 * 既保证准点跟进，也不会把请求密度放大到不合理的程度。
 */
@Slf4j
@Component
public class PollingStateManager {

  @Value("${polling.early-probe-start-sec:25}")
  private long earlyProbeStartSec;

  @Value("${polling.core-radius-sec:5}")
  private long coreRadiusSec;

  @Value("${polling.late-end-sec:30}")
  private long lateEndSec;

  @Value("${polling.early-interval-sec:8}")
  private int earlyIntervalSec;

  @Value("${polling.core-interval-sec:4}")
  private int coreIntervalSec;

  @Value("${polling.late-interval-sec:6}")
  private int lateIntervalSec;

  @Value("${polling.max-backoff-sec:60}")
  private int maxBackoffSec;

  @Value("${polling.interval-ms:300000}")
  private long dataIntervalMs;

  private static final int MIN_HISTORY_FOR_ADAPTATION = 5;
    /** 短周期（≤2 分钟）厂商不启用偏移量自适应，见 shouldPollNow。 */
  private static final long SHORT_INTERVAL_MS = 120_000;

  /** 自适应偏移量的上限：迟到最多 10s、提前最多 2s，避免正反馈把窗口拖到几十秒后。 */
  private static final long MAX_MEAN_OFFSET_MS = 10_000;

  private static final long MAX_EARLY_OFFSET_MS = 2_000;

private static final int MAX_HISTORY = 20;

  /** 上面那组默认阈值所对应的基准上报间隔（5 分钟）。 */
  private static final long BASELINE_INTERVAL_MS = 300_000L;

  @Value("${polling.degraded-timeout-count:2}")
  private int degradedThreshold;

  @Value("${polling.disconnected-timeout-count:5}")
  private int disconnectedThreshold;

  private final ConcurrentHashMap<Long, PollingState> states = new ConcurrentHashMap<>();

  /** Returns true when the scheduler should fire a poll for this connection. */
  public boolean shouldPollNow(Long connectionId) {
    return shouldPollNow(connectionId, dataIntervalMs);
  }

  /**
   * Returns true when the scheduler should fire a poll for this connection.
   *
   * @param intervalMs 该连接的数据上报间隔（微泰 60s，其他 300s）
   */
  public boolean shouldPollNow(Long connectionId, long intervalMs) {
    PollingState s = states.computeIfAbsent(connectionId, id -> new PollingState());
    s.intervalMs = intervalMs;
    Window w = windowFor(intervalMs, maxBackoffSec);

    Instant now = Instant.now();

    if (s.health == ConnectionHealth.DISCONNECTED) {
      return isIntervalElapsed(s, now, w.maxBackoffSec());
    }

    // No baseline yet — poll at early interval to get a first reading
    if (s.nextExpectedTime == null) {
      return isIntervalElapsed(s, now, w.earlyIntervalSec());
    }

    // 短周期厂商（微泰 60s）不要做偏移量自适应：厂商「读数→可见」本身在 13~42s 之间抖动，
    // 把窗口整体后移只会稳定地多等十几秒（实测 +10s 位移会把入库延迟拖到 16s）。
    long meanOffsetMs = intervalMs <= SHORT_INTERVAL_MS ? 0 : computeMeanOffsetMs(s);
    Instant tNext = s.nextExpectedTime.plusMillis(meanOffsetMs);
    long secToNext = now.getEpochSecond() - tNext.getEpochSecond();

    if (secToNext < -w.earlyProbeStartSec()) {
      s.currentPhase = Phase.IDLE;
      return false;
    }

    if (secToNext < -w.coreRadiusSec()) {
      s.currentPhase = Phase.EARLY_PROBE;
      return isIntervalElapsed(s, now, w.earlyIntervalSec());
    }

    if (secToNext <= w.coreRadiusSec()) {
      s.currentPhase = Phase.CORE_WINDOW;
      return isIntervalElapsed(s, now, w.coreIntervalSec());
    }

    if (secToNext <= w.lateEndSec()) {
      s.currentPhase = Phase.LATE_CHASE;
      return isIntervalElapsed(s, now, w.lateIntervalSec());
    }

    // TIMEOUT_BACKOFF
    s.currentPhase = Phase.TIMEOUT_BACKOFF;
    int backoff = computeBackoffSec(s, w);
    boolean fire = isIntervalElapsed(s, now, backoff);
    if (fire) {
      s.consecutiveTimeouts++;
      if (s.consecutiveTimeouts >= disconnectedThreshold) {
        if (s.health != ConnectionHealth.DISCONNECTED) {
          log.warn(
              "[PollingState] connection={} DISCONNECTED ({} timeouts)",
              connectionId,
              s.consecutiveTimeouts);
          s.health = ConnectionHealth.DISCONNECTED;
        }
      } else if (s.consecutiveTimeouts >= degradedThreshold) {
        s.health = ConnectionHealth.DEGRADED;
      }
    }
    return fire;
  }

  /**
   * New data arrived — reset counters, record offset, advance window.
   *
   * @return true if this was a recovery from DISCONNECTED state (caller should backfill).
   */
  public boolean onDataReceived(Long connectionId, Instant receiveTime) {
    PollingState s = states.computeIfAbsent(connectionId, id -> new PollingState());

    boolean wasDisconnected = (s.health == ConnectionHealth.DISCONNECTED);

    if (s.nextExpectedTime != null) {
      long offsetMs = Instant.now().toEpochMilli() - s.nextExpectedTime.toEpochMilli();
      // 手机离线补传时会一次补很多条，这类「迟到几分钟」的样本会把窗口带偏，直接丢弃。
      long intervalMs = s.intervalMs > 0 ? s.intervalMs : dataIntervalMs;
      if (offsetMs >= -intervalMs / 2 && offsetMs <= intervalMs / 2) {
        s.offsetHistory.addLast(offsetMs);
        if (s.offsetHistory.size() > MAX_HISTORY) {
          s.offsetHistory.removeFirst();
        }
      } else {
        s.offsetHistory.clear();
      }
    }

    if (wasDisconnected) {
      log.info("[PollingState] connection={} RECOVERED — backfill needed", connectionId);
      s.offsetHistory.clear();
    }

    long intervalMs = s.intervalMs > 0 ? s.intervalMs : dataIntervalMs;
    s.lastReceiveTime = receiveTime;
    s.nextExpectedTime = receiveTime.plusMillis(intervalMs);
    s.consecutiveTimeouts = 0;
    s.health = ConnectionHealth.HEALTHY;
    s.currentPhase = Phase.IDLE;
    log.debug(
        "[PollingState] connection={} nextExpected={} (interval={}s)",
        connectionId,
        s.nextExpectedTime,
        intervalMs / 1000);
    return wasDisconnected;
  }

  /**
   * @return true if this connection has never received data (cold start).
   */
  public boolean isColdStart(Long connectionId) {
    PollingState s = states.get(connectionId);
    return s == null || s.lastReceiveTime == null;
  }

  /**
   * Poll succeeded but returned no new data (same reading as before). Keeps the connection healthy
   * so we don't falsely degrade into DISCONNECTED.
   *
   * <p>IMPORTANT: we do NOT advance nextExpectedTime here. The data is expected to arrive
   * imminently (sensor just hasn't uploaded yet). Advancing the window to now+interval would cause
   * the scheduler to enter IDLE and miss the data for minutes. Instead, we let shouldPollNow stay
   * in LATE_CHASE / TIMEOUT_BACKOFF at a reasonable rate until the real data arrives and
   * onDataReceived properly advances the window.
   */
  public void onPollSuccessNoNewData(Long connectionId) {
    PollingState s = states.computeIfAbsent(connectionId, id -> new PollingState());
    s.consecutiveTimeouts = 0;
    if (s.health != ConnectionHealth.HEALTHY) {
      log.info(
          "[PollingState] connection={} restored to HEALTHY (poll OK, no new data)", connectionId);
    }
    s.health = ConnectionHealth.HEALTHY;
  }

  /** A poll threw an exception. */
  public void onError(Long connectionId) {
    PollingState s = states.computeIfAbsent(connectionId, id -> new PollingState());
    s.consecutiveTimeouts++;
    if (s.consecutiveTimeouts >= disconnectedThreshold) {
      s.health = ConnectionHealth.DISCONNECTED;
    }
  }

  public void remove(Long connectionId) {
    states.remove(connectionId);
  }

  public Phase getCurrentPhase(Long connectionId) {
    PollingState s = states.get(connectionId);
    return s == null ? Phase.IDLE : s.currentPhase;
  }

  public ConnectionHealth getHealth(Long connectionId) {
    PollingState s = states.get(connectionId);
    return s == null ? ConnectionHealth.HEALTHY : s.health;
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private boolean isIntervalElapsed(PollingState s, Instant now, int intervalSec) {
    if (s.lastFireTime == null
        || now.getEpochSecond() - s.lastFireTime.getEpochSecond() >= intervalSec) {
      s.lastFireTime = now;
      return true;
    }
    return false;
  }

  private int computeBackoffSec(PollingState s, Window w) {
    int base = w.earlyIntervalSec() * 2;
    int backoff =
        (int) Math.min(base * Math.pow(1.5, s.consecutiveTimeouts), w.maxBackoffSec());
    return Math.max(backoff, base);
  }

  /** 夹紧自适应偏移量，避免正反馈把窗口越推越晚（见 computeMeanOffsetMs 注释）。 */
  static long clampOffsetMs(long meanOffsetMs) {
    return Math.max(-MAX_EARLY_OFFSET_MS, Math.min(MAX_MEAN_OFFSET_MS, meanOffsetMs));
  }

  /**
   * 平均偏移量：厂商「读数时间 → 数据可被拉到」的固定滞后，用来自动对齐轮询窗口。
   *
   * <p>必须夹紧：偏移量的测量值 = 我们发现新数据的时间 − 预期时间，本身就包含我们自己的轮询延迟，
   * 若不加限制会形成正反馈（越晚发现 → 窗口越往后 → 更晚发现），最终稳定在几十秒的滞后上。
   * 实测微泰服务器在读数后约 2s 即可拉到，因此把均值限制在 [提前 2s, 迟到 10s]。
   */
  private long computeMeanOffsetMs(PollingState s) {
    if (s.offsetHistory.size() < MIN_HISTORY_FOR_ADAPTATION) {
      return 0;
    }
    long mean = (long) s.offsetHistory.stream().mapToLong(Long::longValue).average().orElse(0);
    return clampOffsetMs(mean);
  }

  /**
   * 按上报间隔等比缩放各阶段阈值。
   *
   * <p>以 5 分钟为基准：微泰 60s 时窗口约为「提前 6s 进入 early probe，±2s 核心窗口，12s 后进入退避」，
   * 单分钟内的请求量控制在个位数；欧泰/硅基维持原有默认值不变。
   */
  private Window windowFor(long intervalMs, int configuredMaxBackoffSec) {
    double scale = Math.min(1.0, Math.max(0.1, intervalMs / (double) BASELINE_INTERVAL_MS));
    long intervalSecLong = Math.max(1L, intervalMs / 1000L);
    int intervalSec = clampInt(intervalSecLong, 1, Integer.MAX_VALUE);

    int lateEnd = (int) clamp(Math.round(lateEndSec * scale), 10, lateEndSec);
    int lateInterval = clampInt(lateIntervalSec * scale, 2, lateIntervalSec);

    // 短周期厂商（微泰 60s）：数据「读数时间 → 厂商接口可见」实测有 15~40s 的上传延迟，
    // 而 appCreateTime 是手机时间（只比 appTime 大 2s），不能用来判断可见性。
    // 若沿用 5 分钟厂商的 ±(6/2/10)s 窗口，就会落到退避阶梯上（+18s/+27s），额外多等十几秒。
    // 因此短周期时把「迟到追赶」窗口拉到数据真正可能出现的时间段，追赶间隔 4s。
    if (intervalSec <= 120) {
      lateEnd = Math.max(lateEnd, Math.min(50, Math.round(intervalSec * 0.85f)));
      lateInterval = 3;
    }

    return new Window(
        clamp(Math.round(earlyProbeStartSec * scale), 6, earlyProbeStartSec),
        clamp(Math.round(coreRadiusSec * scale), 2, coreRadiusSec),
        lateEnd,
        clampInt(earlyIntervalSec * scale, 2, earlyIntervalSec),
        clampInt(coreIntervalSec * scale, 2, coreIntervalSec),
        lateInterval,
        clampInt(configuredMaxBackoffSec * scale, 20, configuredMaxBackoffSec));
  }

  private int clampInt(double value, int min, int max) {
    return Math.max(min, Math.min(max, (int) Math.round(value)));
  }

  private long clamp(long value, long min, long max) {
    return Math.max(min, Math.min(max, value));
  }

  private int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  // ── inner types ─────────────────────────────────────────────────────────

  public enum Phase {
    IDLE,
    EARLY_PROBE,
    CORE_WINDOW,
    LATE_CHASE,
    TIMEOUT_BACKOFF
  }

  public enum ConnectionHealth {
    HEALTHY,
    DEGRADED,
    DISCONNECTED
  }

  /** 某个上报间隔下的一组阶段阈值（秒）。 */
  private record Window(
      long earlyProbeStartSec,
      long coreRadiusSec,
      long lateEndSec,
      int earlyIntervalSec,
      int coreIntervalSec,
      int lateIntervalSec,
      int maxBackoffSec) {}

  private static class PollingState {
    Instant lastReceiveTime;
    Instant nextExpectedTime;
    Phase currentPhase = Phase.IDLE;
    ConnectionHealth health = ConnectionHealth.HEALTHY;
    Instant lastFireTime;
    int consecutiveTimeouts = 0;
    long intervalMs = 0;
    final LinkedList<Long> offsetHistory = new LinkedList<>();
  }
}
