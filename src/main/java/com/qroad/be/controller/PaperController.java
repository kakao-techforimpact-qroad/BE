package com.qroad.be.controller;

import com.qroad.be.dto.PaperCreateRequestDTO;
import com.qroad.be.dto.PublicationDetailResponse;
import com.qroad.be.dto.PublicationJobStartResponse;
import com.qroad.be.dto.PublicationListResponse;
import com.qroad.be.dto.PublicationProgressDto;
import com.qroad.be.security.AdminPrincipal;
import com.qroad.be.service.PaperService;
import com.qroad.be.service.PublicationJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class PaperController {

    private static final String JOB_NOT_FOUND_MESSAGE = "\uC874\uC7AC\uD558\uC9C0 \uC54A\uAC70\uB098 \uB9CC\uB8CC\uB41C \uC791\uC5C5\uC785\uB2C8\uB2E4";

    private final PaperService paperService;
    private final PublicationJobService publicationJobService;

    /**
     * 발행된 신문 목록 조회 API.
     */
    @GetMapping("/publications")
    public ResponseEntity<?> getPublications(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @AuthenticationPrincipal AdminPrincipal admin) {

        Long adminId = admin != null ? admin.getAdminId() : null;
        if (adminId == null) {
            return unauthorizedResponse();
        }

        PublicationListResponse response = paperService.getPublications(page, limit, adminId);
        return ResponseEntity.ok(response);
    }

    /**
     * 단건 발행 상세 조회 API.
     */
    @GetMapping("/publications/{paperId}")
    public ResponseEntity<?> getPublicationDetail(@PathVariable Long paperId) {
        PublicationDetailResponse response = paperService.getPublicationDetail(paperId);
        return ResponseEntity.ok(response);
    }

    /**
     * 발행 비동기 작업 시작 API.
     * 요청 즉시 jobId를 반환하고 실제 생성은 백그라운드에서 수행한다.
     */
    @PostMapping("/publications")
    public ResponseEntity<?> startPublicationJob(
            @RequestBody PaperCreateRequestDTO request,
            @AuthenticationPrincipal AdminPrincipal admin) {
        Long adminId = admin != null ? admin.getAdminId() : null;
        if (adminId == null) {
            return unauthorizedResponse();
        }

        String jobId = publicationJobService.start(request, adminId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(PublicationJobStartResponse.builder().jobId(jobId).build());
    }

    /**
     * 발행 비동기 작업 진행률 조회 API.
     * 프론트는 DONE/FAILED까지 polling한다.
     */
    @GetMapping("/publications/{jobId}/progress")
    public ResponseEntity<?> getPublicationProgress(
            @PathVariable String jobId,
            @AuthenticationPrincipal AdminPrincipal admin) {
        Long adminId = admin != null ? admin.getAdminId() : null;
        if (adminId == null) {
            return unauthorizedResponse();
        }

        try {
            PublicationProgressDto response = publicationJobService.getProgress(jobId);
            return ResponseEntity.ok(response);
        } catch (NoSuchElementException e) {
            return notFoundResponse(JOB_NOT_FOUND_MESSAGE);
        }
    }

    private ResponseEntity<?> unauthorizedResponse() {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("success", false);
        errorResponse.put("message", "\uB85C\uADF8\uC778\uC774 \uD544\uC694\uD569\uB2C8\uB2E4");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }

    private ResponseEntity<?> notFoundResponse(String message) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("success", false);
        errorResponse.put("message", message);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }
}
