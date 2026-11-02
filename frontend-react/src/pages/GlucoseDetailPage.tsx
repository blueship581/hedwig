import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Typography, Segmented, Button, Skeleton, Alert, message } from 'antd';
import { ArrowLeftOutlined, SyncOutlined, ReloadOutlined } from '@ant-design/icons';
import { Link, useParams } from 'react-router-dom';
import { motion } from 'framer-motion';
import { getHistory, syncHistory } from '../api/glucose';
import { GlucoseReading } from '../types';
import { parseTimestampMs, glucoseColor } from '../utils/time';
import GlucoseChart from '../components/GlucoseChart';
import GlucoseTable from '../components/GlucoseTable';
import Freshness from '../components/Freshness';

const { Title, Text } = Typography;

const RANGE_OPTIONS = [
  { label: '6 小时', value: 6 },
  { label: '12 小时', value: 12 },
  { label: '24 小时', value: 24 },
  { label: '48 小时', value: 48 },
  { label: '72 小时', value: 72 },
];

const LOW = 3.9;
const HIGH = 10;

const GlucoseDetailPage: React.FC = () => {
  const { id: subjectId } = useParams<{ id: string }>();
  const [hoursBack, setHoursBack] = useState(24);
  const [readings, setReadings] = useState<GlucoseReading[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [syncMsg, setSyncMsg] = useState<string | null>(null);

  const fetchData = useCallback(
    async (hours: number) => {
      if (!subjectId) return;
      setLoading(true);
      setError(null);
      try {
        const data = await getHistory(subjectId);
        const cutoff = Date.now() - hours * 3600 * 1000;
        setReadings(
          data.filter((r) => {
            const ms = parseTimestampMs(r.readingTime);
            return ms !== null && ms >= cutoff;
          }),
        );
      } catch (e: any) {
        setError(e.message);
      } finally {
        setLoading(false);
      }
    },
    [subjectId],
  );

  useEffect(() => {
    fetchData(hoursBack);
  }, [fetchData, hoursBack]);

  const handleSync = async () => {
    if (!subjectId) return;
    setSyncing(true);
    setSyncMsg(null);
    setError(null);
    try {
      const result = await syncHistory(subjectId);
      setSyncMsg(`同步完成，新增 ${result.syncedCount} 条记录`);
      fetchData(hoursBack);
    } catch (e: any) {
      setError(`同步失败：${e.message}`);
    } finally {
      setSyncing(false);
    }
  };

  const stats = useMemo(() => {
    const count = readings.length;
    if (count === 0) {
      return { count: 0, avg: null as number | null, latest: null as GlucoseReading | null, tir: null as number | null, low: 0, high: 0 };
    }
    const sum = readings.reduce((acc, r) => acc + r.glucoseMmol, 0);
    const latest = readings.reduce<GlucoseReading | null>((best, r) => {
      if (!best) return r;
      const a = parseTimestampMs(r.readingTime) ?? 0;
      const b = parseTimestampMs(best.readingTime) ?? 0;
      return a > b ? r : best;
    }, null);
    const low = readings.filter((r) => r.glucoseMmol < LOW).length;
    const high = readings.filter((r) => r.glucoseMmol > HIGH).length;
    return {
      count,
      avg: sum / count,
      latest,
      tir: Math.round(((count - low - high) / count) * 100),
      low,
      high,
    };
  }, [readings]);

  const latestMs = stats.latest ? parseTimestampMs(stats.latest.readingTime) : null;

  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35 }}
      className="detail-page"
    >
      <Link to="/" className="detail-back">
        <ArrowLeftOutlined /> 返回仪表盘
      </Link>

      <header className="detail-head">
        <div>
          <span className="section-eyebrow">History</span>
          <Title level={3} className="detail-title">
            血糖详情
          </Title>
          <Text type="secondary" className="detail-sub">
            目标范围 {LOW}–{HIGH} mmol/L · 共 {stats.count} 条记录
          </Text>
        </div>
        <div className="detail-actions">
          <Segmented
            value={hoursBack}
            options={RANGE_OPTIONS}
            onChange={(v) => setHoursBack(v as number)}
          />
          <Button icon={<ReloadOutlined />} onClick={() => fetchData(hoursBack)}>
            刷新
          </Button>
          <Button type="primary" icon={<SyncOutlined />} loading={syncing} onClick={handleSync}>
            {syncing ? '同步中' : '回填历史'}
          </Button>
        </div>
      </header>

      {syncMsg && (
        <Alert type="success" message={syncMsg} showIcon closable onClose={() => setSyncMsg(null)} />
      )}

      {loading ? (
        <Skeleton active paragraph={{ rows: 10 }} />
      ) : error ? (
        <Alert type="error" message={`加载失败：${error}`} showIcon />
      ) : (
        <>
          <div className="detail-stats">
            <div className="detail-stat">
              <span className="status-label">最新</span>
              <span
                className="detail-stat-value"
                style={{ color: stats.latest ? glucoseColor(stats.latest.glucoseMmol) : undefined }}
              >
                {stats.latest ? stats.latest.glucoseMmol.toFixed(1) : '—'}
                <span className="detail-stat-unit">mmol/L</span>
              </span>
              {latestMs !== null && (
                <Freshness at={new Date(latestMs).toISOString()} className="detail-stat-note" />
              )}
            </div>

            <div className="detail-stat">
              <span className="status-label">平均</span>
              <span className="detail-stat-value">
                {stats.avg !== null ? stats.avg.toFixed(1) : '—'}
                <span className="detail-stat-unit">mmol/L</span>
              </span>
              <span className="detail-stat-note">区间均值</span>
            </div>

            <div className="detail-stat">
              <span className="status-label">达标率 TIR</span>
              <span className="detail-stat-value detail-stat-accent">
                {stats.tir !== null ? `${stats.tir}%` : '—'}
              </span>
              <span className="detail-stat-note">
                {stats.low} 次偏低 · {stats.high} 次偏高
              </span>
            </div>

            <div className="detail-stat">
              <span className="status-label">记录数</span>
              <span className="detail-stat-value">{stats.count}</span>
              <span className="detail-stat-note">最近 {hoursBack} 小时</span>
            </div>
          </div>

          <section className="detail-panel">
            <div className="section-head">
              <span className="section-eyebrow">Trend</span>
              <h4 className="section-title">血糖趋势</h4>
            </div>
            <GlucoseChart readings={readings} />
          </section>

          <section className="detail-panel">
            <div className="section-head">
              <span className="section-eyebrow">Records</span>
              <h4 className="section-title">数据明细</h4>
            </div>
            <GlucoseTable readings={readings} />
          </section>
        </>
      )}
    </motion.div>
  );
};

export default GlucoseDetailPage;
