package com.qroad.be.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

@Component
public class UserUuidCookieFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "qroad_uid";
    public static final String REQUEST_ATTR_USER_UUID = "qroadUserUuid";

    private static final Duration COOKIE_MAX_AGE = Duration.ofDays(365);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String userUuid = extractValidUserUuid(request);

        if (userUuid == null) {
            userUuid = UUID.randomUUID().toString();
            addUserUuidCookie(request, response, userUuid);
        }

        request.setAttribute(REQUEST_ATTR_USER_UUID, userUuid);
        filterChain.doFilter(request, response);
    }

    private String extractValidUserUuid(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (!COOKIE_NAME.equals(cookie.getName())) {
                continue;
            }

            String value = cookie.getValue();
            if (isValidUuid(value)) {
                return value;
            }
            return null;
        }
        return null;
    }

    private boolean isValidUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void addUserUuidCookie(HttpServletRequest request, HttpServletResponse response, String userUuid) {
        boolean secure = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));

        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, userUuid)
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .sameSite("Lax")
                .maxAge(COOKIE_MAX_AGE)
                .build();

        response.addHeader("Set-Cookie", cookie.toString());
    }
}
