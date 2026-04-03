package com.qroad.be.controller;

import com.qroad.be.dto.EmotionRequestDTO;
import com.qroad.be.dto.EmotionResponseDTO;
import com.qroad.be.security.UserUuidCookieFilter;
import com.qroad.be.service.ArticleEmotionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

        // 사용자 식별자 추출 (qroad_uid UUID 기반)
        String userIdentifier = getUserIdentifier(httpRequest);

        EmotionResponseDTO response = articleEmotionService.toggleEmotion(
                articleId,
                request.getEmotionType(),
                userIdentifier);

        return ResponseEntity.ok(response);
    }

    private String getUserIdentifier(HttpServletRequest request) {
        Object attr = request.getAttribute(UserUuidCookieFilter.REQUEST_ATTR_USER_UUID);
        if (attr instanceof String uuid && !uuid.isBlank()) {
            log.debug("사용자 식별자 (UUID): {}", uuid);
            return uuid;
        }
        throw new IllegalStateException("qroad_uid 사용자 식별자를 찾을 수 없습니다.");
    }
}
