package com.blueship581.hedwig.exception;

public class BadRequestException extends ApiException {

    public BadRequestException(String userMessage) {
        super(ErrorCode.BAD_REQUEST, userMessage);
    }

    public BadRequestException(String userMessage, String logMessage) {
        super(ErrorCode.BAD_REQUEST, userMessage, logMessage);
    }
}
