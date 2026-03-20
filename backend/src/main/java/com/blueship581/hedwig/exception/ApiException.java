package com.blueship581.hedwig.exception;

import lombok.Getter;

@Getter
public abstract class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String userMessage;

    protected ApiException(ErrorCode errorCode, String userMessage) {
        this(errorCode, userMessage, userMessage, null);
    }

    protected ApiException(ErrorCode errorCode, String userMessage, Throwable cause) {
        this(errorCode, userMessage, userMessage, cause);
    }

    protected ApiException(ErrorCode errorCode, String userMessage, String logMessage) {
        this(errorCode, userMessage, logMessage, null);
    }

    protected ApiException(ErrorCode errorCode, String userMessage, String logMessage, Throwable cause) {
        super(logMessage == null || logMessage.isBlank() ? userMessage : logMessage, cause);
        this.errorCode = errorCode;
        this.userMessage = userMessage;
    }
}
