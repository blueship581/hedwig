import React, { useState } from 'react';
import { Button, Typography, Popconfirm, Tag, Select, message } from 'antd';
import { DeleteOutlined, RightOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { VendorConnection, VendorType, TokenStatus } from '../types';
import { updatePollingInterval } from '../api/vendors';
import { formatRemainingTime } from '../utils/time';
import { pollingIntervalOptions, vendorDisplayName } from '../utils/vendor';

const { Text } = Typography;

interface Props {
  connection: VendorConnection;
  onDelete: (id: number) => void;
  onUpdated?: () => void;
  index?: number;
}

const dotColor: Record<TokenStatus, string> = {
  [TokenStatus.ACTIVE]: '#4ecdc4',
  [TokenStatus.EXPIRING_SOON]: '#e0a84b',
  [TokenStatus.EXPIRED]: '#e05c5c',
};

const statusLabel: Record<TokenStatus, { text: string; color: string }> = {
  [TokenStatus.ACTIVE]: { text: '在线', color: '#4ecdc4' },
  [TokenStatus.EXPIRING_SOON]: { text: '即将过期', color: '#e0a84b' },
  [TokenStatus.EXPIRED]: { text: '已过期', color: '#e05c5c' },
};

const VendorCard: React.FC<Props> = ({ connection, onDelete, onUpdated, index = 0 }) => {
  const navigate = useNavigate();
  const c = connection;
  const [savingInterval, setSavingInterval] = useState(false);

  const sensorRemaining = c.sensorExpiresAt ? formatRemainingTime(c.sensorExpiresAt) : null;
  const dot = dotColor[c.tokenStatus];
  const status = statusLabel[c.tokenStatus];

  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: index * 0.06, duration: 0.3 }}
      className="dashboard-device-row"
    >
      {/* Status dot + vendor name */}
      <div className="dashboard-device-left">
        <span
          className="dashboard-device-dot"
          style={{ background: dot, boxShadow: `0 0 6px ${dot}` }}
        />
        <div>
          <div className="dashboard-device-vendor">{vendorDisplayName(c.vendorType)}</div>
          <div className="dashboard-device-subject">
            {c.primarySubjectName || '未发现监测对象'}
          </div>
        </div>
      </div>

      {/* Center info */}
      <div className="dashboard-device-center">
        {sensorRemaining && (
          <Tag
            bordered={false}
            style={{
              background: 'rgba(78, 205, 196, 0.1)',
              color: sensorRemaining === '已过期' ? '#e05c5c' : '#4ecdc4',
              borderRadius: 12,
              fontSize: 12,
            }}
          >
            传感器 {sensorRemaining}
          </Tag>
        )}
        <Tag
          bordered={false}
          style={{
            background: `${status.color}15`,
            color: status.color,
            borderRadius: 12,
            fontSize: 12,
          }}
        >
          {status.text}
        </Tag>
        <Select
          size="small"
          variant="borderless"
          loading={savingInterval}
          value={c.effectivePollingIntervalSeconds ?? c.defaultPollingIntervalSeconds ?? 300}
          options={pollingIntervalOptions(c.vendorType)}
          style={{ width: 104, fontSize: 12 }}
          popupMatchSelectWidth={false}
          title="轮询频率：多久向厂商查询一次新血糖"
          onChange={async (seconds) => {
            setSavingInterval(true);
            try {
              await updatePollingInterval(c.id, seconds);
              message.success('轮询频率已更新');
              onUpdated?.();
            } catch (e: any) {
              message.error(e.message);
            } finally {
              setSavingInterval(false);
            }
          }}
        />
      </div>

      {/* Actions */}
      <div className="dashboard-device-actions">
        {c.primarySubjectId && (
          <Button
            type="link"
            size="small"
            icon={<RightOutlined />}
            onClick={() => navigate(`/glucose/${c.primarySubjectId}`)}
          >
            查看
          </Button>
        )}
        <Popconfirm
          title="确认断开连接？"
          onConfirm={() => onDelete(c.id)}
          okText="确认"
          cancelText="取消"
        >
          <Button type="text" size="small" danger icon={<DeleteOutlined />}>
            断开
          </Button>
        </Popconfirm>
      </div>
    </motion.div>
  );
};

export default VendorCard;
