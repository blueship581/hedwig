package com.blueship581.hedwig.dto;

import com.blueship581.hedwig.domain.enums.VendorType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ConnectByLoginRequest {

    @NotNull(message = "厂商类型不能为空")
    private VendorType vendorType;

    @NotBlank(message = "厂商账号不能为空")
    private String username;

    @NotBlank(message = "厂商密码不能为空")
    private String password;
}
