import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Skeleton } from 'antd';
import { useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { getHistory, getLatest } from '../api/glucose';
import { glucoseColor, glucoseClass, trendArrow, parseTimestampMs } from '../utils/time';
import { useNow } from '../hooks/useNow';
import Freshness from './Freshness';
import Sparkline, { SparkPoint } from './Sparkline';
import GlucoseRangeBar from './GlucoseRangeBar';
import { vendorDisplayName } from '../utils/vendor';
import type { GlucoseReading, VendorType } from '../types';

interface Props {
  subjectId: number;
  subjectName: string;
  vendorType: VendorType | string;
  /** 该连接的轮询间隔（秒），用于「下次刷新」倒计时 */
  pollIntervalSeconds?: number;
}

const SPARK_HOURS = 3;

const GlucoseHero: React.FC<Props> = ({
  subjectId,
  subjectName,
  vendorType,
  pollIntervalSeconds = 300,
}) => {
  const navigate = useNavigate();
  const now = useNow(1000);
  const [reading, setReading] = useState<GlucoseReading | null>(null);
  const [history, setHistory] = useState<GlucoseReading[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchLatest = useCallback(async () => {
    const data = await getLatest(subjectId);
    setReading(data);
    setLoading(false);
  }, [subjectId]);

  const fetchHistory = useCallback(async () => {
    try {
      const data = await getHistory(String(subjectId));
      const cutoff = Date.now() - SPARK_HOURS * 3600 * 1000;
      setHistory(
        data.filter((r) => {
          const ms = parseTimestampMs(r.readingTime);
          return ms !== null && ms >= cutoff;
        }),
      );
    } catch {
      /* 迷你趋势失败不影响主读数 */
    }
  }, [subjectId]);

  useEffect(() => {
    fetchLatest();
    fetchHistory();
    const latestTimer = window.setInterval(fetchLatest, 20_000);
    const historyTimer = window.setInterval(fetchHistory, 60_000);
    return () => {
      window.clearInterval(latestTimer);
      window.clearInterval(historyTimer);
    };
  }, [fetchLatest, fetchHistory]);

  const sparkPoints = useMemo<SparkPoint[]>(
    () =>
      history
        .map((r) => {
          const t = parseTimestampMs(r.readingTime);
          return t === null ? null : { t, v: r.glucoseMmol };
        })
        .filter((p): p is SparkPoint => p !== null)
        .sort((a, b) => a.t - b.t),
    [history],
  );

  const nextPollIn = useMemo(() => {
    const ms = reading ? parseTimestampMs(reading.readingTime) : null;
    if (ms === null) return null;
    const due = ms + pollIntervalSeconds * 1000;
    return Math.max(0, Math.round((due - now) / 1000));
  }, [reading, pollIntervalSeconds, now]);

  if (loading) {
    return (
      <div className="hero-card hero-card-loading">
        <Skeleton active paragraph={{ rows: 2 }} title={{ width: 140 }} />
      </div>
    );
  }

  if (!reading) {
    return (
      <button
        type="button"
        className="hero-card hero-card-empty"
        onClick={() => navigate(`/glucose/${subjectId}`)}
      >
        <span className="hero-empty-title">{subjectName}</span>
        <span className="hero-empty-desc">还没有收到血糖数据，确认传感器已激活并靠近手机</span>
      </button>
    );
  }

  const cls = glucoseClass(reading.glucoseMmol);
  const color = glucoseColor(reading.glucoseMmol);
  const delta = reading.delta;

  return (
    <motion.article
      initial={{ opacity: 0, y: 14 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.45, ease: [0.22, 1, 0.36, 1] }}
      className={`hero-card hero-${cls}`}
      onClick={() => navigate(`/glucose/${subjectId}`)}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') navigate(`/glucose/${subjectId}`);
      }}
    >
      <header className="hero-head">
        <span className="hero-subject">{subjectName}</span>
        <span className="hero-vendor">{vendorDisplayName(vendorType as VendorType)}</span>
      </header>

      <div className="hero-body">
        <div className="hero-main">
          <span className="hero-value" style={{ color }}>
            {reading.glucoseMmol.toFixed(1)}
          </span>
          <div className="hero-side">
            <span className="hero-unit">mmol/L</span>
            <span className="hero-trend" style={{ color }}>
              {trendArrow(reading.trendDirection)}
              {delta !== undefined && delta !== null && (
                <span className="hero-delta">
                  {delta > 0 ? '+' : ''}
                  {delta.toFixed(1)}
                </span>
              )}
            </span>
          </div>
        </div>
        <div className="hero-spark" aria-hidden="true">
          <Sparkline points={sparkPoints} color={color} ariaLabel={`${subjectName} 近 3 小时趋势`} />
          <span className="hero-spark-caption">近 3 小时</span>
        </div>
      </div>

      <GlucoseRangeBar value={reading.glucoseMmol} />

      <footer className="hero-foot">
        <Freshness at={reading.readingTime} />
        <span className="hero-foot-sep">·</span>
        <span className="hero-next">
          {nextPollIn === 0 ? '正在刷新' : nextPollIn !== null ? `下次 ${nextPollIn}s` : '等待下一轮'}
        </span>
        <span className="hero-link">查看详情 →</span>
      </footer>
    </motion.article>
  );
};

export default GlucoseHero;
