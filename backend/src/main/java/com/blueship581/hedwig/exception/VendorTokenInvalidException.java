package com.blueship581.hedwig.exception;

/**
 * 厂商访问令牌已失效（过期、被顶下线、被吊销）。
 *
 * <p>与普通的 {@link VendorException} 区分开，调度器捕获后会把连接标记为 {@code EXPIRED}，停止继续轮询，
 * 直到用户重新连接或令牌监控任务恢复成功。
 */
public class VendorTokenInvalidException extends VendorException {

  public VendorTokenInvalidException(String message) {
    super(ErrorCode.VENDOR_TOKEN_INVALID, message);
  }
}
