package com.blueship581.hedwig.service;

import com.blueship581.hedwig.domain.entity.GatewayUser;
import com.blueship581.hedwig.domain.entity.MonitoredSubject;
import com.blueship581.hedwig.domain.entity.VendorConnection;
import com.blueship581.hedwig.domain.enums.TokenStatus;
import com.blueship581.hedwig.domain.repository.GatewayUserRepository;
import com.blueship581.hedwig.domain.repository.MonitoredSubjectRepository;
import com.blueship581.hedwig.domain.repository.VendorConnectionRepository;
import com.blueship581.hedwig.dto.ConnectByLoginRequest;
import com.blueship581.hedwig.dto.ConnectByTokenRequest;
import com.blueship581.hedwig.dto.VendorConnectionDto;
import com.blueship581.hedwig.exception.AuthException;
import com.blueship581.hedwig.exception.ResourceNotFoundException;
import com.blueship581.hedwig.exception.VendorException;
import com.blueship581.hedwig.vendor.client.VendorClient;
import com.blueship581.hedwig.vendor.client.VendorClientFactory;
import com.blueship581.hedwig.vendor.model.VendorLoginRequest;
import com.blueship581.hedwig.vendor.model.VendorSubject;
import com.blueship581.hedwig.vendor.model.VendorTokenInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Comparator;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VendorConnectionService {

    private final VendorConnectionRepository connectionRepository;
    private final MonitoredSubjectRepository subjectRepository;
    private final GatewayUserRepository userRepository;
    private final VendorClientFactory vendorClientFactory;

    @Transactional
    public VendorConnectionDto connectByToken(String username, ConnectByTokenRequest request) {
        log.info("connectByToken: username={}, vendorType={}, accessToken length={}",
                username, request.getVendorType(),
                request.getAccessToken() != null ? request.getAccessToken().length() : 0);
        GatewayUser user = findUser(username);
        VendorClient client = vendorClientFactory.getClient(request.getVendorType());
        VendorTokenInfo tokenInfo = client.validateToken(request.getAccessToken());

        log.info("connectByToken: token valid={}, userId={}, expiresAt={}",
                tokenInfo.isValid(), tokenInfo.getUserId(), tokenInfo.getExpiresAt());

        if (!tokenInfo.isValid()) {
            throw VendorException.invalidAccessToken();
        }

        VendorConnection connection = connectionRepository
                .findByGatewayUserIdAndVendorType(user.getId(), request.getVendorType())
                .orElse(VendorConnection.builder()
                        .gatewayUserId(user.getId())
                        .vendorType(request.getVendorType())
                        .build());

        connection.setAccessToken(tokenInfo.getToken());
        connection.setVendorUserId(tokenInfo.getUserId());
        connection.setTokenExpiresAt(tokenInfo.getExpiresAt());
        connection.setTokenStatus(TokenStatus.ACTIVE);
        connection = connectionRepository.save(connection);

        // Sync monitored subjects
        syncSubjects(connection, client);

        return toDto(connection);
    }

    @Transactional
    public VendorConnectionDto connectByLogin(String username, ConnectByLoginRequest request) {
        GatewayUser user = findUser(username);
        VendorClient client = vendorClientFactory.getClient(request.getVendorType());

        VendorTokenInfo tokenInfo = client.login(VendorLoginRequest.builder()
                .username(request.getUsername())
                .password(request.getPassword())
                .build());

        if (!tokenInfo.isValid()) {
            throw VendorException.invalidLoginResult();
        }

        VendorConnection connection = connectionRepository
                .findByGatewayUserIdAndVendorType(user.getId(), request.getVendorType())
                .orElse(VendorConnection.builder()
                        .gatewayUserId(user.getId())
                        .vendorType(request.getVendorType())
                        .build());

        connection.setAccessToken(tokenInfo.getToken());
        connection.setVendorUserId(tokenInfo.getUserId());
        connection.setTokenExpiresAt(tokenInfo.getExpiresAt());
        connection.setTokenStatus(TokenStatus.ACTIVE);
        connection = connectionRepository.save(connection);

        syncSubjects(connection, client);

        return toDto(connection);
    }

    @Transactional
    public VendorConnectionDto refreshToken(String username, Long connectionId) {
        GatewayUser user = findUser(username);
        VendorConnection connection = connectionRepository.findById(connectionId)
                .filter(c -> c.getGatewayUserId().equals(user.getId()))
                .orElseThrow(() -> ResourceNotFoundException.vendorConnection(connectionId));

        VendorClient client = vendorClientFactory.getClient(connection.getVendorType());
        VendorTokenInfo tokenInfo = client.validateToken(connection.getAccessToken());
        connection.setTokenExpiresAt(tokenInfo.getExpiresAt());
        connection.setTokenStatus(tokenInfo.isValid() ? TokenStatus.ACTIVE : TokenStatus.EXPIRED);
        connectionRepository.save(connection);

        return toDto(connection);
    }

    @Transactional
    public void disconnect(String username, Long connectionId) {
        GatewayUser user = findUser(username);
        VendorConnection connection = connectionRepository.findById(connectionId)
                .filter(c -> c.getGatewayUserId().equals(user.getId()))
                .orElseThrow(() -> ResourceNotFoundException.vendorConnection(connectionId));

        // Deactivate all subjects for this connection
        subjectRepository.findByVendorConnectionId(connection.getId())
                .forEach(s -> {
                    s.setIsActive(false);
                    subjectRepository.save(s);
                });

        connectionRepository.delete(connection);
    }

    public List<VendorConnectionDto> getConnections(String username) {
        GatewayUser user = findUser(username);
        return connectionRepository.findByGatewayUserId(user.getId())
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public void syncSubjects(VendorConnection connection) {
        VendorClient client = vendorClientFactory.getClient(connection.getVendorType());
        syncSubjects(connection, client);
    }

    private void syncSubjects(VendorConnection connection, VendorClient client) {
        try {
            List<VendorSubject> vendorSubjects = client.getMonitoredSubjects(
                    connection.getAccessToken(), connection.getVendorUserId());

            Instant now = Instant.now();
            for (VendorSubject vs : vendorSubjects) {
                Instant sensorExpiresAt = vs.getSensorRestSeconds() != null
                        ? now.plusSeconds(vs.getSensorRestSeconds())
                        : null;

                subjectRepository.findByVendorConnectionIdAndVendorSubjectId(
                        connection.getId(), vs.getSubjectId())
                        .ifPresentOrElse(
                                existing -> {
                                    existing.setDisplayName(vs.getDisplayName());
                                    existing.setVendorDeviceId(vs.getDeviceId());
                                    existing.setIsActive(true);
                                    existing.setSensorExpiresAt(sensorExpiresAt);
                                    subjectRepository.save(existing);
                                },
                                () -> subjectRepository.save(MonitoredSubject.builder()
                                        .vendorConnectionId(connection.getId())
                                        .vendorSubjectId(vs.getSubjectId())
                                        .vendorDeviceId(vs.getDeviceId())
                                        .displayName(vs.getDisplayName())
                                        .isActive(true)
                                        .sensorExpiresAt(sensorExpiresAt)
                                        .build())
                        );
            }
        } catch (Exception e) {
            log.warn("Failed to sync subjects for connection {}: {}", connection.getId(), e.getMessage());
        }
    }

    private GatewayUser findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(AuthException.sessionExpired(username).getMessage()));
    }

    private VendorConnectionDto toDto(VendorConnection c) {
        MonitoredSubject primarySubject = subjectRepository
                .findByVendorConnectionIdAndIsActive(c.getId(), true)
                .stream()
                .min(Comparator.comparing(MonitoredSubject::getId))
                .orElse(null);

        return VendorConnectionDto.builder()
                .id(c.getId())
                .vendorType(c.getVendorType())
                .vendorUserId(c.getVendorUserId())
                .primarySubjectId(primarySubject != null ? primarySubject.getId() : null)
                .primarySubjectName(primarySubject != null ? primarySubject.getDisplayName() : null)
                .tokenStatus(c.getTokenStatus())
                .tokenExpiresAt(c.getTokenExpiresAt())
                .lastSyncedAt(c.getLastSyncedAt())
                .sensorExpiresAt(primarySubject != null ? primarySubject.getSensorExpiresAt() : null)
                .build();
    }
}
