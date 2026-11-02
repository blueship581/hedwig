import React from 'react';

interface Props {
  value: number;
  /** 低血糖阈值 */
  low?: number;
  /** 高血糖阈值 */
  high?: number;
  /** 刻度两端 */
  min?: number;
  max?: number;
}

/**
 * 当前读数的「位置」可视化：一眼看出偏软、达标还是偏高，而不只是一个颜色。
 * 颜色之外还有位置与文字，符合色盲可用的要求。
 */
const GlucoseRangeBar: React.FC<Props> = ({ value, low = 3.9, high = 10, min = 2.5, max = 16 }) => {
  const clamp = (v: number) => Math.min(max, Math.max(min, v));
  const pct = ((clamp(value) - min) / (max - min)) * 100;
  const lowPct = ((low - min) / (max - min)) * 100;
  const highPct = ((high - min) / (max - min)) * 100;

  const zone = value < low ? 'low' : value > high ? 'high' : 'target';

  return (
    <div className={`range-bar range-bar-${zone}`}>
      <div className="range-bar-track" aria-hidden="true">
        <span className="range-bar-zone range-bar-zone-low" style={{ width: `${lowPct}%` }} />
        <span
          className="range-bar-zone range-bar-zone-target"
          style={{ width: `${highPct - lowPct}%` }}
        />
        <span className="range-bar-zone range-bar-zone-high" style={{ width: `${100 - highPct}%` }} />
        <span className="range-bar-marker" style={{ left: `${pct}%` }} />
      </div>
      <div className="range-bar-labels">
        <span className="range-bar-edge">{min.toFixed(1)}</span>
        <span className="range-bar-threshold" style={{ left: `${lowPct}%` }}>
          {low.toFixed(1)}
        </span>
        <span className="range-bar-threshold" style={{ left: `${highPct}%` }}>
          {high.toFixed(1)}
        </span>
        <span className="range-bar-edge range-bar-edge-end">{max.toFixed(1)}</span>
      </div>
    </div>
  );
};

export default GlucoseRangeBar;
