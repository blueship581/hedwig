import React, { useCallback, useEffect, useState } from 'react';
import { Button, Typography, Row, Col, Skeleton, Empty, message, Card, Space, Alert } from 'antd';
import { PlusOutlined, CloudUploadOutlined } from '@ant-design/icons';
import { motion } from 'framer-motion';
import {
  listTargets,
  createTarget,
  updateTarget,
  deleteTarget,
  toggleTargetStatus,
  nightscoutSync,
} from '../api/nightscout';
import { getConnections } from '../api/vendors';
import { parseTimestampMs } from '../utils/time';
import { formatAge } from '../components/Freshness';
import type { NightscoutTarget, NightscoutTargetRequest } from '../types/nightscout';
import type { VendorConnection } from '../types';
import NightscoutTargetCard from '../components/NightscoutTargetCard';
import NightscoutTargetModal from '../components/NightscoutTargetModal';

const { Title, Paragraph, Text } = Typography;

const SettingsPage: React.FC = () => {
  const [targets, setTargets] = useState<NightscoutTarget[]>([]);
  const [connections, setConnections] = useState<VendorConnection[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Modal state
  const [modalOpen, setModalOpen] = useState(false);
  const [editingTarget, setEditingTarget] = useState<NightscoutTarget | null>(null);
  const [modalLoading, setModalLoading] = useState(false);

  // Sync state
  const [syncing, setSyncing] = useState(false);

  const fetchData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [targetList, connList] = await Promise.all([
        listTargets(),
        getConnections(),
      ]);
      setTargets(targetList);
      setConnections(connList);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // 从 connections 中提取 subjects
  const subjects = connections
    .filter((c) => c.primarySubjectId != null)
    .map((c) => ({
      id: c.primarySubjectId!,
      name: c.primarySubjectName ?? `对象 ${c.primarySubjectId}`,
    }));

  // CRUD handlers
  const handleCreate = () => {
    setEditingTarget(null);
    setModalOpen(true);
  };

  const handleEdit = (target: NightscoutTarget) => {
    setEditingTarget(target);
    setModalOpen(true);
  };

  const handleModalOk = async (values: NightscoutTargetRequest) => {
    setModalLoading(true);
    try {
      if (editingTarget) {
        // 编辑：如果 apiSecret 为空则不传
        const payload: Partial<NightscoutTargetRequest> = { ...values };
        if (!payload.apiSecret) {
          delete payload.apiSecret;
        }
        await updateTarget(editingTarget.id, payload);
        message.success('更新成功');
      } else {
        await createTarget(values);
        message.success('创建成功');
      }
      setModalOpen(false);
      fetchData();
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setModalLoading(false);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteTarget(id);
      message.success('已删除');
      fetchData();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const handleToggle = async (id: number) => {
    try {
      const updated = await toggleTargetStatus(id);
      message.success(updated.status === 'ACTIVE' ? '已启用' : '已停用');
      fetchData();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const handleSync = async () => {
    setSyncing(true);
    try {
      const { count, message: msg } = await nightscoutSync();
      if (count > 0) {
        message.success(`${msg}，共上传 ${count} 条数据`);
      } else {
        message.info(msg);
      }
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setSyncing(false);
    }
  };

  const errorCount = targets.filter((t) => Boolean(t.lastErrorMessage)).length;
  const latestPush = targets
    .map((t) => t.lastSuccessAt || t.lastPushAt)
    .filter((v): v is string => Boolean(v))
    .sort()
    .pop();
  const latestPushMs = latestPush ? parseTimestampMs(latestPush) : null;
  const latestPushText =
    latestPushMs === null ? '—' : formatAge(Math.max(0, Date.now() - latestPushMs));

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.3 }}
      className="detail-page"
    >
      <header className="detail-head">
        <div>
          <span className="section-eyebrow">Nightscout</span>
          <Title level={3} className="detail-title">
            推送配置
          </Title>
          <Text type="secondary" className="detail-sub">
            血糖数据会按分钟推送到这里的 Nightscout 服务
          </Text>
        </div>
        <div className="detail-actions">
          <Button icon={<CloudUploadOutlined />} loading={syncing} onClick={handleSync}>
            {syncing ? '同步中…' : '手动同步'}
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
            添加目标
          </Button>
        </div>
      </header>

      {!loading && !error && targets.length > 0 && (
        <div className="detail-stats">
          <div className="detail-stat">
            <span className="status-label">目标</span>
            <span className="detail-stat-value">{targets.length}</span>
            <span className="detail-stat-note">已配置的 Nightscout</span>
          </div>
          <div className="detail-stat">
            <span className="status-label">启用中</span>
            <span className="detail-stat-value detail-stat-accent">
              {targets.filter((t) => t.status !== 'DISABLED').length}
            </span>
            <span className="detail-stat-note">正在接收数据</span>
          </div>
          <div className="detail-stat">
            <span className="status-label">最近推送</span>
            <span className="detail-stat-value">
              {latestPushText}
            </span>
            <span className="detail-stat-note">所有目标里最新的一次</span>
          </div>
          <div className="detail-stat">
            <span className="status-label">异常</span>
            <span className={`detail-stat-value ${errorCount > 0 ? 'detail-stat-warn' : ''}`}>
              {errorCount}
            </span>
            <span className="detail-stat-note">含错误信息的目标</span>
          </div>
        </div>
      )}

      {/* 内容区 */}
      {loading ? (
        <Row gutter={[16, 16]}>
          {[1, 2].map((i) => (
            <Col xs={24} sm={12} lg={8} key={i}>
              <Skeleton active paragraph={{ rows: 5 }} />
            </Col>
          ))}
        </Row>
      ) : error ? (
        <Alert type="error" message={`加载失败：${error}`} showIcon />
      ) : targets.length === 0 ? (
        <motion.div
          initial={{ opacity: 0, scale: 0.97 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ duration: 0.3 }}
          className="dashboard-panel dashboard-empty-panel"
        >
          <div className="dashboard-empty-owl">🌙</div>
          <h3 className="dashboard-empty-title">还没有 Nightscout 目标</h3>
          <p className="dashboard-empty-desc">
            添加一个目标后，血糖数据会在入库后自动推送过去，箭头趋势也会一起同步。
          </p>
          <div className="empty-channel-list">
            {[
              { name: '站点地址', how: 'https://你的 Nightscout 域名' },
              { name: 'API Secret', how: 'Nightscout 的 api_secret' },
              { name: '绑定对象', how: '选择要推送的监测对象' },
            ].map((c) => (
              <div className="empty-channel" key={c.name}>
                <span className="empty-channel-name">{c.name}</span>
                <span className="empty-channel-how">{c.how}</span>
              </div>
            ))}
          </div>
          <Button type="primary" size="large" icon={<PlusOutlined />} onClick={handleCreate}>
            添加第一个目标
          </Button>
        </motion.div>
      ) : (
        <Row gutter={[16, 16]} className="settings-grid">
          {targets.map((target, idx) => (
            <Col xs={24} sm={12} lg={8} key={target.id}>
              <NightscoutTargetCard
                target={target}
                onEdit={handleEdit}
                onDelete={handleDelete}
                onToggle={handleToggle}
                index={idx}
              />
            </Col>
          ))}
        </Row>
      )}

      {/* 创建/编辑弹窗 */}
      <NightscoutTargetModal
        open={modalOpen}
        editingTarget={editingTarget}
        connections={connections}
        subjects={subjects}
        confirmLoading={modalLoading}
        onOk={handleModalOk}
        onCancel={() => setModalOpen(false)}
      />
    </motion.div>
  );
};

export default SettingsPage;
