package com.blueship581.hedwig.exception;

public class ConflictException extends ApiException {

    public ConflictException(String userMessage) {
        super(ErrorCode.RESOURCE_CONFLICT, userMessage);
    }

    public ConflictException(String userMessage, String logMessage) {
        super(ErrorCode.RESOURCE_CONFLICT, userMessage, logMessage);
    }

    public static ConflictException usernameAlreadyExists(String username) {
        return new ConflictException(
                "该用户名已被占用，请更换后重试",
                "用户名已存在: " + username
        );
    }
}
