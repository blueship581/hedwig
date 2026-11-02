import React, { useEffect } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { ConfigProvider, App as AntApp, theme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { AnimatePresence } from 'framer-motion';
import { useThemeStore } from './store/themeStore';
import { useAuthStore } from './store/authStore';
import { darkTheme, lightTheme } from './theme/tokens';
import Layout from './components/Layout';
import LoginPage from './pages/LoginPage';
import RegisterPage from './pages/RegisterPage';
import DashboardPage from './pages/DashboardPage';
import VendorConnectPage from './pages/VendorConnectPage';
import GlucoseDetailPage from './pages/GlucoseDetailPage';
import SettingsPage from './pages/SettingsPage';

const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  if (!isAuthenticated()) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
};

const App: React.FC = () => {
  const { isDark } = useThemeStore();
  const themeConfig = isDark ? darkTheme : lightTheme;

  // CSS 里的明暗变量全部挂在 [data-theme] 上；不写这个属性，切换主题时只有 antd 组件会变。
  useEffect(() => {
    document.documentElement.dataset.theme = isDark ? 'dark' : 'light';
    document.documentElement.style.colorScheme = isDark ? 'dark' : 'light';
  }, [isDark]);

  return (
    <ConfigProvider theme={themeConfig} locale={zhCN}>
      <AntApp>
        <div className="app-root">
          <BrowserRouter>
            <AnimatePresence mode="wait">
              <Routes>
                <Route path="/login" element={<LoginPage />} />
                <Route path="/register" element={<RegisterPage />} />
                <Route
                  element={
                    <ProtectedRoute>
                      <Layout />
                    </ProtectedRoute>
                  }
                >
                  <Route path="/" element={<DashboardPage />} />
                  <Route path="/connect" element={<VendorConnectPage />} />
                  <Route path="/glucose/:id" element={<GlucoseDetailPage />} />
                  <Route path="/settings" element={<SettingsPage />} />
                </Route>
                <Route
                  path="*"
                  element={
                    <div style={{ textAlign: 'center', paddingTop: 60 }}>
                      <h2 style={{ color: '#7a8599' }}>{'404 \u2014 \u9875\u9762\u4e0d\u5b58\u5728'}</h2>
                      <a href="/">{'\u8fd4\u56de\u9996\u9875'}</a>
                    </div>
                  }
                />
              </Routes>
            </AnimatePresence>
          </BrowserRouter>
        </div>
      </AntApp>
    </ConfigProvider>
  );
};

export default App;
