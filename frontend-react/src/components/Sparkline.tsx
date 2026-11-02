import React, { useMemo } from 'react';

export interface SparkPoint {
  t: number;
  v: number;
}

interface Props {
  points: SparkPoint[];
  width?: number;
  height?: number;
  /** 目标区间，用于底色带 */
  low?: number;
  high?: number;
  color?: string;
  ariaLabel?: string;
}

/**
 * 极简 SVG 迷你趋势线：只表达“最近几小时怎么走”，不承担精确读数职责。
 * 用 viewBox + preserveAspectRatio=none 自适应宽度，避免额外依赖图表库。
 */
const Sparkline: React.FC<Props> = ({
  points,
  width = 320,
  height = 72,
  low = 3.9,
  high = 10,
  color = '#4ecdc4',
  ariaLabel,
}) => {
  const geom = useMemo(() => {
    if (points.length < 2) return null;

    const values = points.map((p) => p.v);
    const dataMin = Math.min(...values);
    const dataMax = Math.max(...values);
    // 只按数据本身定纵轴，趋势形状才看得出来；目标区间用底色带表达（允许被裁到边界外）
    const pad = Math.max(0.35, (dataMax - dataMin) * 0.3);
    const min = dataMin - pad;
    const max = dataMax + pad;

    const t0 = points[0].t;
    const t1 = points[points.length - 1].t;
    const span = t1 - t0 || 1;

    const x = (t: number) => ((t - t0) / span) * width;
    const y = (v: number) => height - ((v - min) / (max - min || 1)) * height;

    const line = points
      .map((p, i) => `${i === 0 ? 'M' : 'L'}${x(p.t).toFixed(2)},${y(p.v).toFixed(2)}`)
      .join(' ');

    const area = `${line} L${width},${height} L0,${height} Z`;

    const bandTopRaw = y(high);
    const bandBottomRaw = y(low);
    const bandTop = Math.max(0, Math.min(height, bandTopRaw));
    const bandBottom = Math.max(0, Math.min(height, bandBottomRaw));

    return {
      line,
      area,
      bandTop,
      bandHeight: Math.max(0, bandBottom - bandTop),
      lastX: x(points[points.length - 1].t),
      lastY: y(points[points.length - 1].v),
    };
  }, [points, width, height, low, high]);

  if (!geom) {
    return <div className="sparkline sparkline-empty" style={{ height }} aria-hidden="true" />;
  }

  const gradientId = `spark-${Math.round(low * 10)}-${Math.round(high * 10)}`;

  return (
    <svg
      className="sparkline"
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      role="img"
      aria-label={ariaLabel ?? '最近血糖趋势'}
    >
      <defs>
        <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity="0.28" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      {geom.bandHeight > 0 && (
        <rect
          x="0"
          y={geom.bandTop}
          width={width}
          height={geom.bandHeight}
          fill="rgba(78, 205, 196, 0.07)"
        />
      )}
      <path d={geom.area} fill={`url(#${gradientId})`} />
      <path d={geom.line} fill="none" stroke={color} strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
      <circle cx={geom.lastX} cy={geom.lastY} r="3.5" fill={color} className="sparkline-dot" />
    </svg>
  );
};

export default Sparkline;
