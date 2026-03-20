package com.blueship581.hedwig.domain.enums;

import com.blueship581.hedwig.exception.BadRequestException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum VendorType {
    OTTAI("OTTAI"),
    SISENSING("SISENSING");

    private final String jsonValue;

    VendorType(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    @JsonCreator
    public static VendorType fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("厂商类型不能为空");
        }

        String normalized = value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);

        return switch (normalized) {
            case "OTTAI" -> OTTAI;
            case "SISENSING", "SI_SENSING" -> SISENSING;
            default -> throw new BadRequestException(
                    "不支持的厂商类型，请检查后重试",
                    "不支持的厂商类型: " + value
            );
        };
    }

    @JsonValue
    public String toJsonValue() {
        return jsonValue;
    }
}
