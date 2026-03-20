package com.blueship581.hedwig.controller;

import com.blueship581.hedwig.dto.ConnectByLoginRequest;
import com.blueship581.hedwig.dto.ConnectByTokenRequest;
import com.blueship581.hedwig.dto.VendorConnectionDto;
import com.blueship581.hedwig.service.VendorConnectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vendors")
@RequiredArgsConstructor
public class VendorController {

    private final VendorConnectionService vendorConnectionService;

    @GetMapping("/connections")
    public ResponseEntity<List<VendorConnectionDto>> getConnections(
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(vendorConnectionService.getConnections(user.getUsername()));
    }

    @PostMapping("/connections/token")
    public ResponseEntity<VendorConnectionDto> connectByToken(
            @AuthenticationPrincipal UserDetails user,
            @Valid @RequestBody ConnectByTokenRequest request) {
        return ResponseEntity.ok(vendorConnectionService.connectByToken(user.getUsername(), request));
    }

    @PostMapping("/connections/login")
    public ResponseEntity<VendorConnectionDto> connectByLogin(
            @AuthenticationPrincipal UserDetails user,
            @Valid @RequestBody ConnectByLoginRequest request) {
        return ResponseEntity.ok(vendorConnectionService.connectByLogin(user.getUsername(), request));
    }

    @PostMapping("/connections/{id}/refresh")
    public ResponseEntity<VendorConnectionDto> refreshToken(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable Long id) {
        return ResponseEntity.ok(vendorConnectionService.refreshToken(user.getUsername(), id));
    }

    @DeleteMapping("/connections/{id}")
    public ResponseEntity<Void> disconnect(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable Long id) {
        vendorConnectionService.disconnect(user.getUsername(), id);
        return ResponseEntity.noContent().build();
    }
}
