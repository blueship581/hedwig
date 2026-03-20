package com.blueship581.hedwig.service;

import com.blueship581.hedwig.domain.entity.GatewayUser;
import com.blueship581.hedwig.domain.repository.GatewayUserRepository;
import com.blueship581.hedwig.dto.AuthResponse;
import com.blueship581.hedwig.dto.LoginRequest;
import com.blueship581.hedwig.dto.RegisterRequest;
import com.blueship581.hedwig.exception.ConflictException;
import com.blueship581.hedwig.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final GatewayUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw ConflictException.usernameAlreadyExists(request.getUsername());
        }

        GatewayUser user = GatewayUser.builder()
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .build();

        userRepository.save(user);

        String token = jwtTokenProvider.generateToken(user.getUsername());
        return AuthResponse.builder()
                .token(token)
                .username(user.getUsername())
                .message("注册成功")
                .build();
    }

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        String token = jwtTokenProvider.generateToken(request.getUsername());
        return AuthResponse.builder()
                .token(token)
                .username(request.getUsername())
                .message("登录成功")
                .build();
    }
}
