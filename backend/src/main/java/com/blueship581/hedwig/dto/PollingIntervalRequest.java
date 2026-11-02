package com.blueship581.hedwig.dto;

import lombok.Data;

/** 轮询间隔更新请求；{@code seconds} 为空表示恢复厂商默认值。 */
@Data
public class PollingIntervalRequest {
  private Integer seconds;
}
