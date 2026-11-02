import { VendorType } from '../types';

/** 厂商展示名，前端统一从这里取，避免各组件各写一份 switch。 */
export function vendorDisplayName(vt: VendorType): string {
  switch (vt) {
    case VendorType.OTTAI:
      return '欧泰';
    case VendorType.SISENSING:
      return '硅基轻享';
    case VendorType.MICROTECH:
      return '微泰';
    default:
      return String(vt);
  }
}

/**
 * 轮询间隔选项（秒）。
 * 微泰每 1 分钟出一个点，默认 1 分钟；其他厂商本身就是 5 分钟一个点，档位从 3 分钟起。
 */
export function pollingIntervalOptions(vt: VendorType): { label: string; value: number }[] {
  const minutes = vt === VendorType.MICROTECH ? [1, 2, 3, 5] : [3, 5, 10];
  return minutes.map((m) => ({ label: `${m} 分钟`, value: m * 60 }));
}

/** 是否支持账号密码直连（欧泰只能贴 token）。 */
export function vendorSupportsLogin(vt: VendorType): boolean {
  return vt === VendorType.SISENSING || vt === VendorType.MICROTECH;
}
