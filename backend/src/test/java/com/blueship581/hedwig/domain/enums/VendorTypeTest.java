package com.blueship581.hedwig.domain.enums;

import com.alibaba.fastjson2.JSON;
import com.blueship581.hedwig.dto.ConnectByTokenRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VendorTypeTest {

    @Test
    void acceptsLegacySiSensingAlias() {
        ConnectByTokenRequest request = JSON.parseObject("""
                {"vendorType":"SI_SENSING","accessToken":"demo-token"}
                """, ConnectByTokenRequest.class);

        assertEquals(VendorType.SISENSING, request.getVendorType());
    }

    @Test
    void acceptsMicroTechAliases() {
        assertEquals(VendorType.MICROTECH, VendorType.fromValue("MICROTECH"));
        assertEquals(VendorType.MICROTECH, VendorType.fromValue("micro_tech"));
        assertEquals(VendorType.MICROTECH, VendorType.fromValue("weitai"));
        assertEquals(VendorType.MICROTECH, VendorType.fromValue("aidex-x"));
    }

    @Test
    void microTechPollsEveryMinuteByDefault() {
        assertEquals(60, VendorType.MICROTECH.getDefaultPollingIntervalSeconds());
        assertEquals(300, VendorType.OTTAI.getDefaultPollingIntervalSeconds());
        assertEquals(300, VendorType.SISENSING.getDefaultPollingIntervalSeconds());
    }

    @Test
    void serializesMicroTechBackToItsStableJsonValue() {
        assertEquals("MICROTECH", JSON.toJSONString(VendorType.MICROTECH).replace("\"", ""));
    }
}
