package com.qroad.be.security;

import com.qroad.be.security.UserUuidCookieFilter;
import com.qroad.be.uuid.MetricsService;
import com.qroad.be.uuid.UserAccessService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class UserTrackingFilter extends OncePerRequestFilter {

    private final UserAccessService userAccessService;
    private final MetricsService metricsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String uuid = (String) request.getAttribute(UserUuidCookieFilter.REQUEST_ATTR_USER_UUID);

        boolean isNewUser = isNewUser(request);

        String path = request.getRequestURI();

        // 불필요한 경로 필터링
        if (shouldTrack(path)) {
            userAccessService.save(uuid, path, isNewUser);
            metricsService.recordRequest(path);

            if (isNewUser) {
                metricsService.recordNewUser();
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isNewUser(HttpServletRequest request) {
        return request.getCookies() == null ||
                java.util.Arrays.stream(request.getCookies())
                        .noneMatch(c -> UserUuidCookieFilter.COOKIE_NAME.equals(c.getName()));
    }

    private boolean shouldTrack(String path) {
        return !path.startsWith("/actuator")
                && !path.startsWith("/favicon")
                && !path.startsWith("/static");
    }
}
