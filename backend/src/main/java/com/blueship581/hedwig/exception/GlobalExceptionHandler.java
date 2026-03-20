package com.blueship581.hedwig.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Objects;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException e, HttpServletRequest request) {
        ErrorCode errorCode = e.getErrorCode();
        if (errorCode == ErrorCode.UPSTREAM_SERVICE_ERROR || errorCode == ErrorCode.INTERNAL_SERVER_ERROR) {
            log.error("API exception: {}", e.getMessage(), e);
        } else {
            log.warn("API exception: {}", e.getMessage());
        }
        return error(errorCode, e.getUserMessage(), request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleBadCredentials(BadCredentialsException e,
                                                                 HttpServletRequest request) {
        return error(ErrorCode.AUTHENTICATION_FAILED, null, request);
    }

    @ExceptionHandler(UsernameNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleUsernameNotFound(UsernameNotFoundException e,
                                                                   HttpServletRequest request) {
        log.warn("Username not found: {}", e.getMessage());
        return error(ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(MethodArgumentNotValidException e,
                                                                      HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)
                .distinct()
                .reduce((left, right) -> left + "；" + right)
                .orElse(ErrorCode.VALIDATION_ERROR.getDefaultMessage());
        return error(ErrorCode.VALIDATION_ERROR, message, request);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception e, HttpServletRequest request) {
        log.warn("Bad request: {}", e.getMessage());
        return error(ErrorCode.BAD_REQUEST, e.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException e,
                                                                     HttpServletRequest request) {
        ApiException apiException = unwrapApiException(e);
        if (apiException != null) {
            return error(apiException.getErrorCode(), apiException.getUserMessage(), request);
        }
        return error(ErrorCode.BAD_REQUEST, "请求体格式不正确，请检查后重试", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception e, HttpServletRequest request) {
        log.error("Unexpected error", e);
        return error(ErrorCode.INTERNAL_SERVER_ERROR, null, request);
    }

    private ResponseEntity<ApiErrorResponse> error(ErrorCode errorCode,
                                                   String message,
                                                   HttpServletRequest request) {
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiErrorResponse.of(errorCode, message, request.getRequestURI()));
    }

    private ApiException unwrapApiException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ApiException apiException) {
                return apiException;
            }
            current = current.getCause();
        }
        return null;
    }
}
