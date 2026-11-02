package com.blueship581.hedwig.dto;

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
public class VendorConnectionDto {
  private Long id;
  private VendorType vendorType;
  private String vendorUserId;
  private Long primarySubjectId;
  private String primarySubjectName;
  private TokenStatus tokenStatus;
  private Instant tokenExpiresAt;
  private Instant lastSyncedAt;
  private Instant sensorExpiresAt;

  /** 连接级轮询间隔（秒）的用户设置值；为空表示跟随厂商默认值。 */
  private Integer pollingIntervalSeconds;

  /** 该厂商的默认轮询间隔（秒），用于前端展示与兜底。 */
  private Integer defaultPollingIntervalSeconds;

  /** 实际生效的轮询间隔（秒）= 用户设置值，未设置时取厂商默认值。 */
  private Integer effectivePollingIntervalSeconds;
}
