import { useEffect, useState } from 'react';

/**
 * 每秒刷新的“当前时间”，用于「x 秒前」「还需 x 秒」这类需要持续走的计时文案。
 * 统一用一个 interval，避免每个组件各起一个定时器。
 */
export function useNow(intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(id);
  }, [intervalMs]);

  return now;
}
