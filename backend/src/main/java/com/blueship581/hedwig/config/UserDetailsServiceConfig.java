package com.blueship581.hedwig.config;

import com.blueship581.hedwig.domain.repository.GatewayUserRepository;
import com.blueship581.hedwig.exception.AuthException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@RequiredArgsConstructor
public class UserDetailsServiceConfig {

    private final GatewayUserRepository gatewayUserRepository;

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> gatewayUserRepository.findByUsername(username)
                .map(user -> org.springframework.security.core.userdetails.User.builder()
                        .username(user.getUsername())
                        .password(user.getPasswordHash())
                        .roles("USER")
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException(AuthException.sessionExpired(username).getMessage()));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
