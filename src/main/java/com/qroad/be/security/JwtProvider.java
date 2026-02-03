package com.qroad.be.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;

@Component
public class JwtProvider {

    // ✅ HS256용 시크릿 키 (32바이트 이상 필수)
    private static final String SECRET =
            "jwt-secret-key-jwt-secret-key-jwt-secret-key";

    private final Key key = Keys.hmacShaKeyFor(SECRET.getBytes());

    // 토큰 유효시간 (1시간)
    private static final long EXPIRE_TIME = 1000 * 60 * 60;

    /**
     * 토큰 생성
     */
    public String createToken(Long adminId, String loginId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + EXPIRE_TIME);

        return Jwts.builder()
                .setSubject(adminId.toString())   // 핵심 식별자
                .claim("loginId", loginId)        // 부가 정보
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 토큰 검증
     */
    public boolean validate(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    // 3. 관리자 ID 추출
    public Long getAdminId(String token) {
        return Long.parseLong(
                Jwts.parserBuilder()
                        .setSigningKey(key)
                        .build()
                        .parseClaimsJws(token)
                        .getBody()
                        .getSubject()
        );
    }

    // 4. 로그인 ID 추출
    public String getLoginId(String token) {
        return (String)
                Jwts.parserBuilder()
                        .setSigningKey(key)
                        .build()
                        .parseClaimsJws(token)
                        .getBody()
                        .get("loginId");
    }
}
