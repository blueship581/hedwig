import React, { useMemo } from 'react';
import { Line } from '@ant-design/charts';
import { Empty } from 'antd';
import { GlucoseReading } from '../types';
import { parseTimestampMs } from '../utils/time';
import { useThemeStore } from '../store/themeStore';

interface Props {
  readings: GlucoseReading[];
}

const LOW = 3.9;
const HIGH = 10;

function zoneOf(v: number): 'low' | 'target' | 'high' {
  if (v < LOW) return 'low';
  if (v > HIGH) return 'high';
  return 'target';
}

const ZONE_COLORS: Record<string, string> = {
  low: '#e0a84b',
  target: '#4ecdc4',
  high: '#e05c5c',
};

const GlucoseChart: React.FC<Props> = ({ readings }) => {
  const { isDark } = useThemeStore();

  const data = useMemo(() => {
    const points = readings
      .map((r) => {
        const ms = parseTimestampMs(r.readingTime);
        return ms !== null ? { time: ms, value: r.glucoseMmol } : null;
      })
      .filter(Boolean)
      .sort((a, b) => a!.time - b!.time) as { time: number; value: number }[];

    // 按「低 / 达标 / 高」拆成三条线，跨阈值处把交点补进相邻区间，视觉上仍然连贯。
    // 这样颜色本身就是信息：不用看图例也能看出哪段超标。
    const withZone: { time: number; value: number; zone: string }[] = [];
    points.forEach((p, i) => {
      const zone = zoneOf(p.value);
      if (i > 0) {
        const prev = points[i - 1];
        const prevZone = zoneOf(prev.value);
        if (prevZone !== zone) {
          // 交点同时进入两条线，颜色切换处才不会出现断口
          withZone.push({ time: prev.time, value: prev.value, zone });
          withZone.push({ time: p.time, value: p.value, zone: prevZone });
        }
      }
      withZone.push({ ...p, zone });
    });
    return withZone;
  }, [readings]);

  if (data.length === 0) {
    return <Empty description={'\u6682\u65e0\u6570\u636e'} />;
  }

  const config = {
    data,
    xField: 'time',
    yField: 'value',
    shapeField: 'smooth',
    colorField: 'zone',
    height: 300,
    autoFit: true,
    scale: {
      color: { domain: ['low', 'target', 'high'], range: ['#e0a84b', '#4ecdc4', '#e05c5c'] },
      x: { type: 'time' as const },
      y: { domainMin: 2, domainMax: 20 },
    },
    legend: false,
    axis: {
      x: {
        labelFormatter: (v: string | number | Date) => {
          const d = new Date(v);
          return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
        },
      },
      y: {},
    },
    style: {
      lineWidth: 2,
    },
    tooltip: {
      title: (d: { time: number }) => new Date(d.time).toLocaleString('zh-CN'),
      items: [
        {
          channel: 'y',
          name: '\u8840\u7cd6',
          valueFormatter: (v: number) => `${v.toFixed(1)} mmol/L`,
        },
      ],
    },
    annotations: [
      {
        type: 'lineY',
        yField: 3.9,
        style: { stroke: '#e0a84b', strokeOpacity: 0.5, lineDash: [4, 4] },
      },
      {
        type: 'lineY',
        yField: 10.0,
        style: { stroke: '#e05c5c', strokeOpacity: 0.5, lineDash: [4, 4] },
      },
    ],
    theme: isDark ? 'classicDark' : 'classic',
  };

  return <Line {...(config as any)} />;
};

export default GlucoseChart;
