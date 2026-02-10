package com.qroad.be.controller;

import com.qroad.be.dto.FinalizeUploadRequest;
import com.qroad.be.dto.FinalizeUploadResponse;
import com.qroad.be.dto.PresignedUploadResponse;
import com.qroad.be.security.AdminPrincipal;
import com.qroad.be.service.S3PresignService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class UploadController {

    private final S3PresignService s3PresignService;

    @PostMapping("/publications/upload-url")
    public ResponseEntity<?> createPaperUploadUrl(@AuthenticationPrincipal AdminPrincipal admin) {
        Long adminId = admin != null ? admin.getAdminId() : null;
        if (adminId == null) {
            return unauthorizedResponse();
        }

        PresignedUploadResponse response = s3PresignService.createPdfUploadUrl();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/publications/finalize-file")
    public ResponseEntity<?> finalizePaperFile(
            @RequestBody FinalizeUploadRequest request,
            @AuthenticationPrincipal AdminPrincipal admin) {
        Long adminId = admin != null ? admin.getAdminId() : null;
        if (adminId == null) {
            return unauthorizedResponse();
        }

        FinalizeUploadResponse response = s3PresignService
                .finalizePdfUpload(request.getTempKey(), request.getPublicationId());
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<?> unauthorizedResponse() {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("success", false);
        errorResponse.put("message", "로그인이 필요합니다.");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }
}
