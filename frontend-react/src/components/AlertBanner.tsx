import React from 'react';
import { Alert } from 'antd';
import { WarningOutlined, ClockCircleOutlined } from '@ant-design/icons';
import { motion, AnimatePresence } from 'framer-motion';
import { TokenStatus, VendorConnection } from '../types';
import { vendorDisplayName } from '../utils/vendor';

interface Props {
  connections: VendorConnection[];
}

const AlertBanner: React.FC<Props> = ({ connections }) => {
  const expired = connections.filter((c) => c.tokenStatus === TokenStatus.EXPIRED);
  const expiring = connections.filter((c) => c.tokenStatus === TokenStatus.EXPIRING_SOON);

  if (!expired.length && !expiring.length) return null;

  return (
    <AnimatePresence>
      <div className="dashboard-alert-area" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {expired.length > 0 && (
          <motion.div
            initial={{ opacity: 0, y: -8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -8 }}
          >
            <Alert
              type="error"
              showIcon
              icon={<WarningOutlined />}
              message={`令牌已过期，需要重新连接：${expired.map((c) => vendorDisplayName(c.vendorType)).join('、')}`}
              banner
            />
          </motion.div>
        )}
        {expiring.length > 0 && (
          <motion.div
            initial={{ opacity: 0, y: -8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -8 }}
          >
            <Alert
              type="warning"
              showIcon
              icon={<ClockCircleOutlined />}
              message={`令牌即将过期，请尽快更新：${expiring.map((c) => vendorDisplayName(c.vendorType)).join('、')}`}
              banner
            />
          </motion.div>
        )}
      </div>
    </AnimatePresence>
  );
};

export default AlertBanner;
