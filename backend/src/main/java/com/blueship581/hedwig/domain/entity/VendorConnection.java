package com.blueship581.hedwig.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.blueship581.hedwig.domain.enums.TokenStatus;
import com.blueship581.hedwig.domain.enums.VendorType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("vendor_connections")
public class VendorConnection {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long gatewayUserId;

  private VendorType vendorType;

  private String accessToken;

  private String vendorUserId;

  private Instant tokenExpiresAt;

  @Builder.Default private TokenStatus tokenStatus = TokenStatus.ACTIVE;

  private Instant lastSyncedAt;

  /**
   * 连接级轮询间隔（秒）。为空时回落到厂商默认值，见 {@link
   * VendorType#getDefaultPollingIntervalSeconds()}。
   *
   * <p>微泰默认 60 秒（1 分钟一个血糖点），其他厂商默认 300 秒。
   */
  private Integer pollingIntervalSeconds;
}
