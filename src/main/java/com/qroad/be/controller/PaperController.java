package com.qroad.be.controller;

import com.qroad.be.dto.PublicationDetailResponse;
import com.qroad.be.dto.PublicationListResponse;
import com.qroad.be.dto.QrCodeResponse;
import com.qroad.be.service.PaperService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class PaperController {

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

    /**
     * API 1: 발행된 신문 리스트 조회
     */
    @GetMapping("/publications")
    public ResponseEntity<?> getPublications(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            HttpSession session) {

        // 세션 확인
        // ResponseEntity<?> sessionCheck = checkSession(session);
        // if (sessionCheck != null) {
        // return sessionCheck;
        // }

        PublicationListResponse response = paperService.getPublications(page, limit);
        return ResponseEntity.ok(response);
    }

    /**
     * API 2: 발행 상세 조회
     */
    @GetMapping("/publications/{paperId}")
    public ResponseEntity<?> getPublicationDetail(
            @PathVariable Long paperId,
            HttpSession session) {

        // 세션 확인
        // ResponseEntity<?> sessionCheck = checkSession(session);
        // if (sessionCheck != null) {
        // return sessionCheck;
        // }

        PublicationDetailResponse response = paperService.getPublicationDetail(paperId);
        return ResponseEntity.ok(response);
    }

    /**
     * API 3: QR 발행
     */
    @PostMapping("/qr/{paperId}")
    public ResponseEntity<?> generateQrCode(
            @PathVariable Long paperId,
            HttpSession session) {

        // 세션 확인
        // ResponseEntity<?> sessionCheck = checkSession(session);
        // if (sessionCheck != null) {
        // return sessionCheck;
        // }

        QrCodeResponse response = paperService.generateQrCode(paperId);
        return ResponseEntity.ok(response);
    }

    /**
     * API 4: 신문 지면 생성 (GPT 기반 기사 청킹 및 분석)
     */
    @PostMapping("/publications")
    public ResponseEntity<?> createPaper(
            @RequestBody com.qroad.be.dto.PaperCreateRequestDTO request,
            HttpSession session) {

        // 세션 확인
        ResponseEntity<?> sessionCheck = checkSession(session);
        if (sessionCheck != null) {
            return sessionCheck;
        }

        try {
            // 세션에서 adminId 추출
            Long adminId = (Long) session.getAttribute("adminId");

            com.qroad.be.dto.PaperCreateResponseDTO response = paperService.createPaperWithArticles(request, adminId);

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "신문 지면 생성 중 오류가 발생했습니다: " + e.getMessage());

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
}