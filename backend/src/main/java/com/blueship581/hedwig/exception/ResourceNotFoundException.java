package com.blueship581.hedwig.exception;

public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String message) {
        super(ErrorCode.RESOURCE_NOT_FOUND, message);
    }

    public ResourceNotFoundException(String userMessage, String logMessage) {
        super(ErrorCode.RESOURCE_NOT_FOUND, userMessage, logMessage);
    }

    public static ResourceNotFoundException vendorConnection(Long connectionId) {
        return new ResourceNotFoundException(
                "未找到对应的设备连接，请刷新后重试",
                "设备连接不存在: id=" + connectionId
        );
    }

    public static ResourceNotFoundException monitoredSubject(Long subjectId) {
        return new ResourceNotFoundException(
                "未找到对应的监测对象，请刷新后重试",
                "监测对象不存在: id=" + subjectId
        );
    }

    public static ResourceNotFoundException glucoseReadings(Long subjectId) {
        return new ResourceNotFoundException(
                "暂未查询到该监测对象的血糖数据，请先同步后再试",
                "监测对象血糖数据不存在: subjectId=" + subjectId
        );
    }

    public static ResourceNotFoundException syncedGlucoseReadings(Long subjectId) {
        return new ResourceNotFoundException(
                "同步完成后仍未查询到血糖数据，请稍后再试",
                "同步后仍未获取到监测对象血糖读数: subjectId=" + subjectId
        );
    }
}
