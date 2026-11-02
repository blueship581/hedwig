import React from 'react';
import { parseTimestampMs } from '../utils/time';
import { useNow } from '../hooks/useNow';

export type FreshnessLevel = 'live' | 'fresh' | 'stale' | 'old' | 'unknown';

export function freshnessLevel(ageMs: number | null): FreshnessLevel {
  if (ageMs === null) return 'unknown';
  if (ageMs < 90_000) return 'live';
  if (ageMs < 4 * 60_000) return 'fresh';
  if (ageMs < 12 * 60_000) return 'stale';
  return 'old';
}

export function formatAge(ageMs: number | null): string {
  if (ageMs === null || ageMs < 0) return '—';
  const sec = Math.floor(ageMs / 1000);
  if (sec < 10) return '刚刚';
  if (sec < 60) return `${sec} 秒前`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min} 分钟前`;
  const hour = Math.floor(min / 60);
  if (hour < 24) return `${hour} 小时前`;
  return `${Math.floor(hour / 24)} 天前`;
}

interface Props {
  /** ISO 时间字符串或毫秒时间戳 */
  at?: string | null;
  /** 前缀，例如「更新于」 */
  prefix?: string;
  showDot?: boolean;
  className?: string;
}

/**
 * 实时跳动的数据新鲜度：每秒重算一次，颜色随“数据是否够新”变化。
 * 对远程看护的人来说，这比一个大数字更能回答“现在到底有没有在正常同步”。
 */
const Freshness: React.FC<Props> = ({ at, prefix, showDot = true, className }) => {
  const now = useNow(1000);
  const ms = at ? parseTimestampMs(at) : null;
  const ageMs = ms === null ? null : Math.max(0, now - ms);
  const level = freshnessLevel(ageMs);

  return (
    <span className={`freshness freshness-${level} ${className ?? ''}`.trim()}>
      {showDot && <span className="freshness-dot" aria-hidden="true" />}
      {prefix}
      {formatAge(ageMs)}
    </span>
  );
};

export default Freshness;
