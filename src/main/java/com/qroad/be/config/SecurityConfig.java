package com.qroad.be.config;

import com.qroad.be.security.JwtAuthenticationFilter;
import com.qroad.be.security.JwtProvider;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@AllArgsConstructor
public class SecurityConfig {

    private final JwtProvider jwtProvider;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())   // 기본 로그인 끄기
                .httpBasic(basic -> basic.disable()) // 기본 Basic Auth 끄기
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        // 인증 없이 허용
                        .requestMatchers(
                                "/api/admin/login",
                                "/api/admin/register",
                                "/api/admin/logout",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                // 사용자 페이지
                                "/api/qr/**",
                                "/api/articles/**"
                        ).permitAll()
                        // 그 외는 인증 필요
                        .anyRequest().authenticated()
                )
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtProvider),
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}


