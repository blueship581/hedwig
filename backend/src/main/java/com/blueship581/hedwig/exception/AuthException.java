package com.blueship581.hedwig.exception;

public class AuthException extends ApiException {

    public AuthException(String message) {
        super(ErrorCode.UNAUTHORIZED, message);
    }

    public AuthException(String userMessage, String logMessage) {
        super(ErrorCode.UNAUTHORIZED, userMessage, logMessage);
    }

    public static AuthException sessionExpired(String username) {
        return new AuthException(
                "登录状态已失效，请重新登录",
                "用户上下文不存在: " + username
        );
    }
}
