import React, { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useNow } from '../hooks/useNow';
import { parseTimestampMs, formatRemainingTime } from '../utils/time';
import Freshness from './Freshness';
import type { VendorConnection } from '../types';

interface Props {
  connections: VendorConnection[];
  /** 已配置的 Nightscout 推送目标数量；null 表示还没读出来 */
  nightscoutTargets: number | null;
}

function effectiveInterval(conn: VendorConnection): number {
  return (
    conn.effectivePollingIntervalSeconds ??
    conn.pollingIntervalSeconds ??
    conn.defaultPollingIntervalSeconds ??
    300
  );
}

/**
 * 仪表盘顶部的事实条：数据是否在流动、下一轮何时到、有没有在往 Nightscout 推、传感器还能用多久。
 * 用一条细带而不是四张卡片，避免把「看护」这件事做成报表。
 */
const StatusStrip: React.FC<Props> = ({ connections, nightscoutTargets }) => {
  const navigate = useNavigate();
  const now = useNow(1000);

  const facts = useMemo(() => {
    const synced = connections
      .map((c) => parseTimestampMs(c.lastSyncedAt ?? ''))
      .filter((v): v is number => v !== null);
    const freshest = synced.length > 0 ? Math.max(...synced) : null;

    const nextPolls = connections
      .map((c) => {
        const ms = parseTimestampMs(c.lastSyncedAt ?? '');
        return ms === null ? null : ms + effectiveInterval(c) * 1000 - now;
      })
      .filter((v): v is number => v !== null);
    const nextPoll = nextPolls.length > 0 ? Math.min(...nextPolls) : null;

    const sensors = connections
      .map((c) => c.sensorExpiresAt)
      .filter((v): v is string => Boolean(v));
    const sensor = sensors.length > 0 ? formatRemainingTime(sensors.sort()[0]) : null;

    return { freshest, nextPoll, sensor };
  }, [connections, now]);

  return (
    <div className="status-strip">
      <div className="status-item">
        <span className="status-label">数据</span>
        {facts.freshest === null ? (
          <span className="status-value status-muted">等待首条数据</span>
        ) : (
          <Freshness at={new Date(facts.freshest).toISOString()} className="status-value" prefix="更新于 " />
        )}
      </div>

      <div className="status-item">
        <span className="status-label">下一轮</span>
        <span className="status-value">
          {facts.nextPoll === null
            ? '—'
            : facts.nextPoll <= 0
              ? '刷新中…'
              : `${Math.ceil(facts.nextPoll / 1000)} 秒后`}
        </span>
      </div>

      <button type="button" className="status-item status-item-action" onClick={() => navigate('/settings')}>
        <span className="status-label">Nightscout</span>
        <span className={`status-value ${nightscoutTargets === 0 ? 'status-warn' : ''}`}>
          {nightscoutTargets === null
            ? '—'
            : nightscoutTargets > 0
              ? `推送中 · ${nightscoutTargets} 个目标`
              : '未配置，点击设置'}
        </span>
      </button>

      <div className="status-item">
        <span className="status-label">传感器</span>
        <span className="status-value">{facts.sensor ? `剩余 ${facts.sensor}` : '—'}</span>
      </div>
    </div>
  );
};

export default StatusStrip;
