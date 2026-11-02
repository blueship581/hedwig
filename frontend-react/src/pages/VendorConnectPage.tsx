import React, { useState } from 'react';
import { Form, Input, Button, Typography, Alert, Segmented } from 'antd';
import { ArrowLeftOutlined, LinkOutlined, LockOutlined, MobileOutlined } from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { connectToken, connectLogin } from '../api/vendors';
import { VendorType } from '../types';
import { vendorDisplayName, vendorSupportsLogin } from '../utils/vendor';

const { Title, Text } = Typography;
const { TextArea } = Input;

type AuthMode = 'token' | 'login';

interface ChannelSpec {
  type: VendorType;
  cadence: string;
  how: string;
  note: string;
  loginOnly?: boolean;
}

const CHANNELS: ChannelSpec[] = [
  {
    type: VendorType.MICROTECH,
    cadence: '1 分钟 / 点',
    how: '主账号分享 → 用被分享账号接入',
    note: '微泰单账号只能一处登录，务必用被分享的第二个账号',
  },
  {
    type: VendorType.SISENSING,
    cadence: '5 分钟 / 点',
    how: '账号密码直连，或粘贴 token',
    note: '推荐账号密码直连，能自动续期与同步对象',
  },
  {
    type: VendorType.OTTAI,
    cadence: '5 分钟 / 点',
    how: '微信小程序抓包取 access token',
    note: '暂不支持账号密码，token 过期需重新粘贴',
  },
];

function summary(vendor: VendorType): string {
  switch (vendor) {
    case VendorType.MICROTECH:
      return '输入被分享账号（第二个微泰账号）的手机号与密码。系统会读出主账号授权的数据，手机上的主账号不受影响。';
    case VendorType.OTTAI:
      return '从欧泰微信小程序抓包获取 access token，粘贴到下方。令牌会加密保存在服务端。';
    default:
      return '输入硅基轻享注册手机号和密码即可直连；也可以改用 token 接入。';
  }
}

const VendorConnectPage: React.FC = () => {
  const navigate = useNavigate();
  const [step, setStep] = useState<1 | 2>(1);
  const [vendorType, setVendorType] = useState<VendorType>(VendorType.MICROTECH);
  const [authMode, setAuthMode] = useState<AuthMode>('login');
  const [tokenValue, setTokenValue] = useState('');
  const [vendorUsername, setVendorUsername] = useState('');
  const [vendorPassword, setVendorPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const useLoginMode =
    vendorType === VendorType.MICROTECH ||
    (vendorType === VendorType.SISENSING && authMode === 'login');

  const pickChannel = (type: VendorType) => {
    setVendorType(type);
    setAuthMode(vendorSupportsLogin(type) ? 'login' : 'token');
    setError(null);
    setSuccess(null);
    setStep(2);
  };

  const handleSubmit = async () => {
    setError(null);
    setSuccess(null);

    if (useLoginMode) {
      if (!vendorUsername.trim() || !vendorPassword.trim()) {
        setError(`请输入${vendorDisplayName(vendorType)}账号（手机号）和密码`);
        return;
      }
    } else if (!tokenValue.trim()) {
      setError('请输入访问令牌');
      return;
    }

    setLoading(true);
    try {
      if (useLoginMode) {
        await connectLogin(vendorType, vendorUsername, vendorPassword);
      } else {
        await connectToken(vendorType, tokenValue);
      }
      setSuccess('连接成功，正在返回仪表盘……');
      setTimeout(() => navigate('/'), 1200);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3 }}
      className="detail-page connect-page"
    >
      <Link to="/" className="detail-back">
        <ArrowLeftOutlined /> 返回仪表盘
      </Link>

      <header className="detail-head">
        <div>
          <span className="section-eyebrow">Connect</span>
          <Title level={3} className="detail-title">
            连接血糖监测设备
          </Title>
          <Text type="secondary" className="detail-sub">
            {step === 1 ? '先选渠道，再填认证信息' : `${vendorDisplayName(vendorType)} · 填写认证信息`}
          </Text>
        </div>
        <div className="connect-steps">
          <span className={`connect-step ${step === 1 ? 'is-active' : 'is-done'}`}>1 选择渠道</span>
          <span className="connect-step-sep" />
          <span className={`connect-step ${step === 2 ? 'is-active' : ''}`}>2 完成认证</span>
        </div>
      </header>

      {step === 1 && (
        <div className="channel-grid">
          {CHANNELS.map((ch, i) => (
            <motion.button
              key={ch.type}
              type="button"
              className="channel-card"
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: i * 0.06, duration: 0.3 }}
              onClick={() => pickChannel(ch.type)}
            >
              <div className="channel-card-head">
                <span className="channel-name">{vendorDisplayName(ch.type)}</span>
                <span className="channel-cadence">{ch.cadence}</span>
              </div>
              <span className="channel-how">{ch.how}</span>
              <span className="channel-note">{ch.note}</span>
            </motion.button>
          ))}
        </div>
      )}

      {step === 2 && (
        <section className="connect-panel">
          <div className="connect-panel-head">
            <div>
              <span className="status-label">{vendorDisplayName(vendorType)}</span>
              <p className="connect-summary">{summary(vendorType)}</p>
            </div>
            <Button type="text" onClick={() => setStep(1)} className="connect-switch">
              换一个渠道
            </Button>
          </div>

          {vendorType === VendorType.SISENSING && (
            <Segmented
              className="connect-mode"
              value={authMode}
              options={[
                { label: '账号密码', value: 'login' },
                { label: 'access token', value: 'token' },
              ]}
              onChange={(v) => {
                setAuthMode(v as AuthMode);
                setError(null);
              }}
            />
          )}

          <Form layout="vertical" onFinish={handleSubmit} className="connect-form">
            {useLoginMode ? (
              <>
                <Form.Item label="账号（手机号）" required>
                  <Input
                    size="large"
                    prefix={<MobileOutlined />}
                    value={vendorUsername}
                    onChange={(e) => setVendorUsername(e.target.value)}
                    placeholder={
                      vendorType === VendorType.MICROTECH
                        ? '请输入被分享账号的手机号'
                        : '请输入硅基轻享注册手机号'
                    }
                    autoComplete="username"
                  />
                </Form.Item>
                <Form.Item label="密码" required>
                  <Input.Password
                    size="large"
                    prefix={<LockOutlined />}
                    value={vendorPassword}
                    onChange={(e) => setVendorPassword(e.target.value)}
                    placeholder={
                      vendorType === VendorType.MICROTECH ? '请输入微泰 App 登录密码' : '请输入登录密码'
                    }
                    autoComplete="current-password"
                  />
                </Form.Item>
              </>
            ) : (
              <Form.Item label="access token" required>
                <TextArea
                  value={tokenValue}
                  onChange={(e) => setTokenValue(e.target.value)}
                  placeholder="粘贴 token 值，不要包含 Bearer 前缀"
                  autoSize={{ minRows: 3, maxRows: 6 }}
                />
              </Form.Item>
            )}

            {error && <Alert type="error" message={error} showIcon className="connect-alert" />}
            {success && <Alert type="success" message={success} showIcon className="connect-alert" />}

            <div className="connect-actions">
              <Button
                type="primary"
                size="large"
                icon={useLoginMode ? <LinkOutlined /> : undefined}
                loading={loading}
                onClick={handleSubmit}
              >
                {loading ? '连接中…' : useLoginMode ? '登录并连接' : '验证并连接'}
              </Button>
              <Button size="large" type="text" onClick={() => setStep(1)}>
                暂不连接
              </Button>
            </div>
          </Form>
        </section>
      )}
    </motion.div>
  );
};

export default VendorConnectPage;
