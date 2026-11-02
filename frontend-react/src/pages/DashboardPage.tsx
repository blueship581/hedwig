import React, { useCallback, useEffect, useState } from 'react';
import { Button, Skeleton, Empty, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { getConnections, deleteConnection } from '../api/vendors';
import { listTargets } from '../api/nightscout';
import { VendorConnection } from '../types';
import VendorCard from '../components/VendorCard';
import AlertBanner from '../components/AlertBanner';
import GlucoseHero from '../components/GlucoseHero';
import StatusStrip from '../components/StatusStrip';

function getGreeting(): string {
  const h = new Date().getHours();
  if (h < 6) return '夜深了';
  if (h < 12) return '早上好';
  if (h < 18) return '下午好';
  return '晚上好';
}

function effectiveInterval(conn: VendorConnection): number {
  return (
    conn.effectivePollingIntervalSeconds ??
    conn.pollingIntervalSeconds ??
    conn.defaultPollingIntervalSeconds ??
    300
  );
}

const DashboardPage: React.FC = () => {
  const navigate = useNavigate();
  const [connections, setConnections] = useState<VendorConnection[]>([]);
  const [nightscoutTargets, setNightscoutTargets] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchConnections = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getConnections();
      setConnections(data);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchConnections();
    listTargets()
      .then((targets) => setNightscoutTargets(targets.filter((t) => t.status !== 'DISABLED').length))
      .catch(() => setNightscoutTargets(null));
  }, [fetchConnections]);

  const handleDelete = async (id: number) => {
    try {
      await deleteConnection(id);
      message.success('已断开连接');
      fetchConnections();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const heroConnections = connections.filter((c) => c.primarySubjectId != null);

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.4 }}
      className="dashboard-page"
    >
      {/* ── Page header ── */}
      <motion.header
        className="dashboard-header"
        initial={{ opacity: 0, y: -10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4, delay: 0.05 }}
      >
        <div>
          <h2 className="dashboard-greeting">{getGreeting()}</h2>
          <p className="dashboard-greeting-sub">
            {connections.length > 0
              ? `${connections.length} 个渠道在同步 · 数据每分钟刷新`
              : '连接一个 CGM 设备，开始实时监测'}
          </p>
        </div>
        <Button
          type="default"
          icon={<PlusOutlined />}
          onClick={() => navigate('/connect')}
          className="dashboard-add-btn"
        >
          添加设备
        </Button>
      </motion.header>

      {/* ── Loading skeleton ── */}
      {loading && (
        <div className="dashboard-skeleton-wrap">
          <div className="dashboard-panel">
            <Skeleton active paragraph={{ rows: 3 }} title={{ width: 160 }} />
          </div>
        </div>
      )}

      {/* ── Error state ── */}
      {!loading && error && (
        <div className="dashboard-panel" style={{ textAlign: 'center', padding: 48 }}>
          <Empty description={`加载失败：${error}`} image={Empty.PRESENTED_IMAGE_SIMPLE} />
        </div>
      )}

      {/* ── Empty state ── */}
      {!loading && !error && connections.length === 0 && (
        <motion.div
          initial={{ opacity: 0, scale: 0.97 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ duration: 0.4 }}
          className="dashboard-panel dashboard-empty-panel"
        >
          <div className="dashboard-empty-owl">🦉</div>
          <h3 className="dashboard-empty-title">还没有连接任何设备</h3>
          <p className="dashboard-empty-desc">
            支持欧泰、硅基轻享、微泰三个渠道，连上之后每分钟自动取一次血糖并推送到 Nightscout。
          </p>
          <div className="empty-channel-list">
            {[
              { name: '欧泰', how: '微信小程序抓包拿到 access token' },
              { name: '硅基轻享', how: '账号密码直连，或粘贴 token' },
              { name: '微泰（AiDEX X）', how: '分享给第二个账号，用被分享账号接入' },
            ].map((c) => (
              <div className="empty-channel" key={c.name}>
                <span className="empty-channel-name">{c.name}</span>
                <span className="empty-channel-how">{c.how}</span>
              </div>
            ))}
          </div>
          <Button
            type="primary"
            size="large"
            icon={<PlusOutlined />}
            onClick={() => navigate('/connect')}
          >
            添加第一个设备
          </Button>
        </motion.div>
      )}

      {/* ── Main content ── */}
      {!loading && !error && connections.length > 0 && (
        <>
          <motion.div
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.4, delay: 0.08 }}
          >
            <StatusStrip connections={connections} nightscoutTargets={nightscoutTargets} />
          </motion.div>

          <AlertBanner connections={connections} />

          {heroConnections.length > 0 && (
            <motion.section
              initial={{ opacity: 0, y: 16 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.4, delay: 0.12 }}
              className="dashboard-section"
            >
              <div className="dashboard-hero-grid">
                {heroConnections.map((c) => (
                  <GlucoseHero
                    key={c.id}
                    subjectId={c.primarySubjectId!}
                    subjectName={c.primarySubjectName || `对象 ${c.primarySubjectId}`}
                    vendorType={c.vendorType}
                    pollIntervalSeconds={effectiveInterval(c)}
                  />
                ))}
              </div>
            </motion.section>
          )}

          <motion.section
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.4, delay: 0.2 }}
            className="dashboard-section"
          >
            <div className="section-head">
              <span className="section-eyebrow">Channels</span>
              <h4 className="section-title">设备连接</h4>
            </div>
            <div className="dashboard-device-list">
              {connections.map((conn, idx) => (
                <VendorCard
                  key={conn.id}
                  connection={conn}
                  onDelete={handleDelete}
                  onUpdated={fetchConnections}
                  index={idx}
                />
              ))}
            </div>
          </motion.section>
        </>
      )}
    </motion.div>
  );
};

export default DashboardPage;
