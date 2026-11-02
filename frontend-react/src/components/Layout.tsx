import React, { useState, useEffect } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  Layout as AntLayout,
  Menu,
  Button,
  Space,
  Typography,
  Drawer,
} from 'antd';
import {
  DashboardOutlined,
  ApiOutlined,
  SettingOutlined,
  LogoutOutlined,
  UserOutlined,
  MenuOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
} from '@ant-design/icons';
import { useAuthStore } from '../store/authStore';
import { useThemeStore } from '../store/themeStore';
import ThemeToggle from './ThemeToggle';

const { Sider, Content } = AntLayout;
const { Text } = Typography;

const COLLAPSED_KEY = 'hedwig_sidebar_collapsed';

const Layout: React.FC = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const { username, logout } = useAuthStore();
  const { isDark } = useThemeStore();

  const [collapsed, setCollapsed] = useState(() => {
    const stored = localStorage.getItem(COLLAPSED_KEY);
    return stored === 'true';
  });
  const [isMobile, setIsMobile] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);

  useEffect(() => {
    const mql = window.matchMedia('(max-width: 768px)');
    const handler = (e: MediaQueryListEvent | MediaQueryList) => {
      setIsMobile(e.matches);
      if (e.matches) setDrawerOpen(false);
    };
    handler(mql);
    mql.addEventListener('change', handler as (e: MediaQueryListEvent) => void);
    return () =>
      mql.removeEventListener('change', handler as (e: MediaQueryListEvent) => void);
  }, []);

  const handleCollapse = (value: boolean) => {
    setCollapsed(value);
    localStorage.setItem(COLLAPSED_KEY, String(value));
  };

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const menuItems = [
    { key: '/', icon: <DashboardOutlined />, label: '仪表盘' },
    { key: '/connect', icon: <ApiOutlined />, label: '连接设备' },
    { key: '/settings', icon: <SettingOutlined />, label: 'Nightscout配置' },
  ];

  const handleMenuClick = ({ key }: { key: string }) => {
    navigate(key);
    if (isMobile) setDrawerOpen(false);
  };

  const siderContent = (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      {/* Top: Logo */}
      <div
        className={`sidebar-logo${collapsed && !isMobile ? ' sidebar-logo--collapsed' : ''}`}
        onClick={() => navigate('/')}
      >
        <span style={{ fontSize: 24 }}>{'🦉'}</span>
        {(!collapsed || isMobile) && (
          <>
            <Text strong style={{ fontSize: 18, color: '#c8a44e' }}>
              Hedwig
            </Text>
            <Text type="secondary" style={{ fontSize: 12 }}>
              CGM Gateway
            </Text>
          </>
        )}
      </div>

      {/* Middle: Menu */}
      <Menu
        mode="inline"
        selectedKeys={[location.pathname]}
        items={menuItems}
        onClick={handleMenuClick}
        style={{ flex: 1, border: 'none' }}
      />

      {/* Bottom: User info, theme toggle, logout */}
      <div
        className={`sidebar-bottom${collapsed && !isMobile ? ' sidebar-bottom--collapsed' : ''}`}
      >
        <Space
          direction="vertical"
          size="small"
          style={{ width: '100%' }}
          align={collapsed && !isMobile ? 'center' : 'start'}
        >
          {collapsed && !isMobile ? (
            <>
              <Button type="text" icon={<UserOutlined />} size="small" />
              <ThemeToggle />
              <Button
                type="text"
                icon={<LogoutOutlined />}
                size="small"
                danger
                onClick={handleLogout}
              />
            </>
          ) : (
            <>
              <Space>
                <UserOutlined />
                <Text ellipsis style={{ maxWidth: 140 }}>
                  {username}
                </Text>
              </Space>
              <Space
                style={{
                  width: '100%',
                  justifyContent: 'space-between',
                }}
              >
                <ThemeToggle />
                <Button
                  type="text"
                  icon={<LogoutOutlined />}
                  size="small"
                  danger
                  onClick={handleLogout}
                >
                  退出
                </Button>
              </Space>
            </>
          )}
        </Space>
      </div>
    </div>
  );

  return (
    <AntLayout style={{ minHeight: '100vh' }}>
      {/* Mobile: thin top bar + Drawer */}
      {isMobile && (
        <>
          <AntLayout.Header
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              padding: '0 16px',
              height: 48,
              lineHeight: '48px',
            }}
          >
            <Button
              type="text"
              icon={<MenuOutlined />}
              onClick={() => setDrawerOpen(true)}
              style={{ color: 'inherit' }}
            />
            <Space size={4}>
              <span style={{ fontSize: 20 }}>{'🦉'}</span>
              <Text strong style={{ fontSize: 16, color: '#c8a44e' }}>
                Hedwig
              </Text>
            </Space>
            <div style={{ width: 32 }} />
          </AntLayout.Header>
          <Drawer
            placement="left"
            open={drawerOpen}
            onClose={() => setDrawerOpen(false)}
            width={240}
            styles={{ body: { padding: 0 } }}
          >
            {siderContent}
          </Drawer>
        </>
      )}

      {/* Desktop: collapsible Sider */}
      {!isMobile && (
        <Sider
          collapsible
          collapsed={collapsed}
          onCollapse={handleCollapse}
          theme={isDark ? 'dark' : 'light'}
          width={240}
          collapsedWidth={64}
          trigger={
            <div style={{ textAlign: 'center', padding: '12px 0' }}>
              {collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            </div>
          }
          style={{
            overflow: 'auto',
            height: '100vh',
            position: 'sticky',
            top: 0,
            left: 0,
          }}
        >
          {siderContent}
        </Sider>
      )}

      {/* Content area */}
      <AntLayout>
        <Content
          style={{
            padding: 24,
            maxWidth: 1200,
            width: '100%',
            margin: '0 auto',
            overflow: 'auto',
          }}
        >
          <Outlet />
        </Content>
      </AntLayout>
    </AntLayout>
  );
};

export default Layout;
