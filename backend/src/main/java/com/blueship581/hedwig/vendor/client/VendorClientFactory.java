package com.blueship581.hedwig.vendor.client;

import com.blueship581.hedwig.domain.enums.VendorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VendorClientFactory {

  private final OttaiClient ottaiClient;
  private final SiSensingClient siSensingClient;
  private final MicroTechClient microTechClient;

  public VendorClient getClient(VendorType vendorType) {
    return switch (vendorType) {
      case OTTAI -> ottaiClient;
      case SISENSING -> siSensingClient;
      case MICROTECH -> microTechClient;
    };
  }
}
