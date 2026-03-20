package com.blueship581.hedwig.exception;

public class VendorException extends ApiException {

    public VendorException(String message) {
        super(ErrorCode.UPSTREAM_SERVICE_ERROR, message);
    }

    public VendorException(String userMessage, String logMessage) {
        super(ErrorCode.UPSTREAM_SERVICE_ERROR, userMessage, logMessage);
    }

    public VendorException(String message, Throwable cause) {
        super(ErrorCode.UPSTREAM_SERVICE_ERROR, message, message, cause);
    }

    public VendorException(String userMessage, String logMessage, Throwable cause) {
        super(ErrorCode.UPSTREAM_SERVICE_ERROR, userMessage, logMessage, cause);
    }

    public static VendorException invalidAccessToken() {
        return new VendorException("厂商访问令牌无效或已过期，请重新连接后再试");
    }

    public static VendorException invalidLoginResult() {
        return new VendorException("厂商账号登录失败，请核对账号信息后重试");
    }

    public static VendorException unsupportedPasswordLogin(String vendorName) {
        return new VendorException(vendorName + " 暂不支持账号密码直连，请改用访问令牌连接");
    }

    public static VendorException noGlucoseData(String vendorName, String subjectId) {
        return new VendorException(
                "当前监测对象暂无可用血糖数据，请稍后再试",
                vendorName + " 暂无血糖数据: subjectId=" + subjectId
        );
    }

    public static VendorException responseInvalid(String vendorName, String operation, String detail) {
        return new VendorException(
                vendorName + " 服务返回异常，暂时无法" + operation + "，请稍后重试",
                vendorName + " 接口返回异常, operation=" + operation + ", detail=" + detail
        );
    }

    public static VendorException serviceUnavailable(String vendorName, String operation, String detail, Throwable cause) {
        return new VendorException(
                vendorName + " 服务暂时不可用，暂时无法" + operation + "，请稍后重试",
                vendorName + " 接口请求失败, operation=" + operation + ", detail=" + detail,
                cause
        );
    }

    public static VendorException loginFailed(String vendorName, String detail) {
        return new VendorException(
                vendorName + " 账号登录失败，请检查账号和密码后重试",
                vendorName + " 登录失败, detail=" + detail
        );
    }

    public static VendorException tokenValidationFailed(String vendorName, Throwable cause) {
        return new VendorException(
                vendorName + " 令牌校验失败，请稍后重试",
                vendorName + " 令牌校验失败",
                cause
        );
    }
}
