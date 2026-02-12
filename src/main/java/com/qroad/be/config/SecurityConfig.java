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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import org.springframework.http.HttpMethod;
import java.util.List;

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
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                        // 인증 없이 허용
                        .requestMatchers(
                                "/api/admin/login",
                                "/api/admin/register",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                // 사용자 페이지
                                "/api/qr/**",
                                "/api/articles/**"
                        ).permitAll()
                        // 제보 등록은 인증 없이 허용 (POST만)
                        .requestMatchers(HttpMethod.POST, "/api/reports").permitAll()
                        // 그 외는 인증 필요
                        .anyRequest().authenticated()
                )
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtProvider),
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOrigins(List.of(
                "http://localhost:3000",
                "https://qroad.info",
                "https://www.qroad.info",
                "https://api.qroad.info",
                "https://www.api.qroad.info"
        ));

        config.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"
        ));

        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type"
        ));

        config.setExposedHeaders(List.of(
                "Authorization"
        ));

        config.setAllowCredentials(false); // JWT이므로 false

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return source;
    }
}


