export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  password: string;
}

export interface AuthResponse {
  token: string;
  username: string;
}

export enum VendorType {
  OTTAI = 'OTTAI',
  SISENSING = 'SISENSING',
  MICROTECH = 'MICROTECH',
}

export enum TokenStatus {
  ACTIVE = 'ACTIVE',
  EXPIRING_SOON = 'EXPIRING_SOON',
  EXPIRED = 'EXPIRED',
}

export interface VendorConnection {
  id: number;
  vendorType: VendorType;
  vendorUserId?: string;
  primarySubjectId?: number;
  primarySubjectName?: string;
  tokenStatus: TokenStatus;
  tokenExpiresAt?: string;
  lastSyncedAt?: string;
  sensorExpiresAt?: string;
  /** 用户设置的轮询间隔（秒）；为空表示跟随厂商默认值。 */
  pollingIntervalSeconds?: number;
  /** 厂商默认轮询间隔（秒）：微泰 60，欧泰/硅基 300。 */
  defaultPollingIntervalSeconds?: number;
  /** 实际生效的轮询间隔（秒）。 */
  effectivePollingIntervalSeconds?: number;
}

export interface ConnectTokenRequest {
  vendorType: string;
  accessToken: string;
}

export interface ConnectLoginRequest {
  vendorType: string;
  username: string;
  password: string;
}

export enum TrendDirection {
  DOUBLE_UP = 'DOUBLE_UP',
  SINGLE_UP = 'SINGLE_UP',
  FORTY_FIVE_UP = 'FORTY_FIVE_UP',
  FLAT = 'FLAT',
  FORTY_FIVE_DOWN = 'FORTY_FIVE_DOWN',
  SINGLE_DOWN = 'SINGLE_DOWN',
  DOUBLE_DOWN = 'DOUBLE_DOWN',
  NONE = 'NONE',
}

export interface GlucoseReading {
  id?: number;
  monitoredSubjectId?: number;
  glucoseMmol: number;
  glucoseMgdl?: number;
  trendDirection?: TrendDirection;
  delta?: number;
  readingTime: string;
  pushedToNightscout?: boolean;
}

export interface SyncResult {
  syncedCount: number;
  timeRangeStart?: string;
  timeRangeEnd?: string;
}

export interface NightscoutSyncResponse {
  pushed?: number;
}
