package com.qroad.be.controller;

import com.qroad.be.dto.ArticleUpdateRequestDTO;
import com.qroad.be.service.PaperService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/articles")
@RequiredArgsConstructor
public class AdminArticleController {

    private final PaperService paperService;

    /**
     * 세션 확인 메서드
     */
    private ResponseEntity<?> checkSession(HttpSession session) {
        if (session == null || session.getAttribute("adminId") == null) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "로그인이 필요합니다.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
        }
        return null;
    }

    @PostMapping("/{id}")
    public ResponseEntity<?> updateArticle(
            @PathVariable Long id,
            @RequestBody ArticleUpdateRequestDTO request,
            HttpSession session) {

        // 세션 확인
        ResponseEntity<?> sessionCheck = checkSession(session);
        if (sessionCheck != null) {
            return sessionCheck;
        }

        com.qroad.be.dto.ArticleUpdateResponseDTO response = paperService.updateArticle(id, request);
        return ResponseEntity.ok(response);
    }
}
