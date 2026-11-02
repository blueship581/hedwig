import client from './client';
import type { VendorConnection } from '../types';

export async function getConnections(): Promise<VendorConnection[]> {
  const { data } = await client.get<VendorConnection[]>('/api/vendors/connections');
  return data;
}

export async function connectToken(vendorType: string, accessToken: string): Promise<VendorConnection> {
  const { data } = await client.post<VendorConnection>('/api/vendors/connections/token', {
    vendorType,
    accessToken,
  });
  return data;
}

export async function connectLogin(vendorType: string, username: string, password: string): Promise<VendorConnection> {
  const { data } = await client.post<VendorConnection>('/api/vendors/connections/login', {
    vendorType,
    username,
    password,
  });
  return data;
}

export async function deleteConnection(id: number): Promise<void> {
  await client.delete(`/api/vendors/connections/${id}`);
}

/**
 * 调整某个厂商连接的轮询间隔。
 * seconds 传 null 表示恢复厂商默认值（微泰 60s，其他 300s）。
 */
export async function updatePollingInterval(
  id: number,
  seconds: number | null,
): Promise<VendorConnection> {
  const { data } = await client.patch<VendorConnection>(
    `/api/vendors/connections/${id}/polling-interval`,
    { seconds },
  );
  return data;
}
