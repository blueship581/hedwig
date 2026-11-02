package com.blueship581.hedwig.domain.enums;

import com.alibaba.fastjson2.annotation.JSONCreator;
import com.alibaba.fastjson2.annotation.JSONField;
import com.baomidou.mybatisplus.annotation.EnumValue;

import java.util.Locale;

public enum VendorType {
  OTTAI("OTTAI", 300),
  SISENSING("SISENSING", 300),
  /** 微泰 AiDEX X：1 分钟一个血糖点。 */
  MICROTECH("MICROTECH", 60);

  @EnumValue
  @JSONField(value = true)
  private final String jsonValue;

  /** 厂商数据上报的默认节奏（秒），可作为连接级轮询间隔的兜底值。 */
  private final int defaultPollingIntervalSeconds;

  VendorType(String jsonValue, int defaultPollingIntervalSeconds) {
    this.jsonValue = jsonValue;
    this.defaultPollingIntervalSeconds = defaultPollingIntervalSeconds;
  }

  @JSONCreator
  public static VendorType fromValue(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("厂商类型不能为空");
    }

    String normalized = value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);

    return switch (normalized) {
      case "OTTAI" -> OTTAI;
      case "SISENSING", "SI_SENSING" -> SISENSING;
      case "MICROTECH", "MICRO_TECH", "WEITAI", "AIDEX", "AIDEX_X" -> MICROTECH;
      default -> throw new IllegalArgumentException("不支持的厂商类型：" + value);
    };
  }

  public String toJsonValue() {
    return jsonValue;
  }

  public int getDefaultPollingIntervalSeconds() {
    return defaultPollingIntervalSeconds;
  }
}
