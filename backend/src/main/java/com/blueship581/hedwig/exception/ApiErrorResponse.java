package com.blueship581.hedwig.exception;

import java.time.Instant;

public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String error,
        String message,
        String path
) {

    public static ApiErrorResponse of(ErrorCode errorCode, String message, String path) {
        return new ApiErrorResponse(
                Instant.now(),
                errorCode.getStatus().value(),
                errorCode.getCode(),
                errorCode.getError(),
                message == null || message.isBlank() ? errorCode.getDefaultMessage() : message,
                path
        );
    }
}
