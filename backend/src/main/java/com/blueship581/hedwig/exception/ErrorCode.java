package com.blueship581.hedwig.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "请求参数错误", "请求参数不合法，请检查后重试"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "参数校验失败", "请求参数校验未通过，请检查后重试"),
    AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", "认证失败", "用户名或密码错误"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未登录", "请先登录后再操作"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "禁止访问", "您没有权限执行该操作"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "请求的资源不存在或已被删除"),
    RESOURCE_CONFLICT(HttpStatus.CONFLICT, "RESOURCE_CONFLICT", "资源冲突", "当前资源状态冲突，请刷新后重试"),
    UPSTREAM_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "UPSTREAM_SERVICE_ERROR", "上游服务异常", "上游服务暂时不可用，请稍后重试"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "服务器内部错误", "系统繁忙，请稍后重试");

    private final HttpStatus status;
    private final String code;
    private final String error;
    private final String defaultMessage;
}
