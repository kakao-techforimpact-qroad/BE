package com.qroad.be.controller;

import com.qroad.be.dto.EmotionRequestDTO;
import com.qroad.be.dto.EmotionResponseDTO;
import com.qroad.be.dto.EmotionStatsDTO;
import com.qroad.be.service.ArticleEmotionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/articles/{articleId}/emotions")
public class ArticleEmotionController {

    private final ArticleEmotionService articleEmotionService;

    /**
     * 감정 공감 추가/취소 (토글)
     * POST /api/articles/{articleId}/emotions
     */
    @PostMapping
    public ResponseEntity<EmotionResponseDTO> toggleEmotion(
            @PathVariable("articleId") Long articleId,
            @Valid @RequestBody EmotionRequestDTO request,
            HttpServletRequest httpRequest) {
        log.info("감정 토글 API 호출 - articleId: {}, emotionType: {}", articleId, request.getEmotionType());

        // 사용자 식별자 추출 (IP 주소 사용)
        String userIdentifier = getUserIdentifier(httpRequest);

        EmotionResponseDTO response = articleEmotionService.toggleEmotion(
                articleId,
                request.getEmotionType(),
                userIdentifier);

        return ResponseEntity.ok(response);
    }

    /**
     * 기사별 감정 통계 조회
     * GET /api/articles/{articleId}/emotions/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<EmotionStatsDTO> getEmotionStats(
            @PathVariable("articleId") Long articleId) {
        log.info("감정 통계 조회 API 호출 - articleId: {}", articleId);

        EmotionStatsDTO stats = articleEmotionService.getEmotionStats(articleId);

        return ResponseEntity.ok(stats);
    }

    /**
     * 사용자 식별자 추출 (IP 주소 기반)
     * 프록시 환경을 고려하여 X-Forwarded-For 헤더도 확인
     */
    private String getUserIdentifier(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");

        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        log.debug("사용자 식별자(IP): {}", ip);
        return ip;
    }
}
