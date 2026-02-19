package com.qroad.be.controller;

import com.qroad.be.dto.ReportCreateRequestDTO;
import com.qroad.be.dto.ReportListResponseDTO;
import com.qroad.be.dto.ReportResponseDTO;
import com.qroad.be.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    /**
     * 제보 등록 (공개 - 인증 불필요)
     */
    @PostMapping("/api/reports")
    public ResponseEntity<ReportResponseDTO> createReport(@RequestBody ReportCreateRequestDTO request) {
        log.info("제보 등록 요청: title={}", request.getTitle());
        ReportResponseDTO response = reportService.createReport(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 제보 목록 조회 (관리자 전용)
     */
    @GetMapping("/api/admin/reports")
    public ResponseEntity<ReportListResponseDTO> getReports(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String status) {

        ReportListResponseDTO response = reportService.getReports(page, limit, status);
        return ResponseEntity.ok(response);
    }

    /**
     * 제보 상세 조회 (관리자 전용)
     */
    @GetMapping("/api/admin/reports/{reportId}")
    public ResponseEntity<ReportResponseDTO> getReport(@PathVariable Long reportId) {
        ReportResponseDTO response = reportService.getReport(reportId);
        return ResponseEntity.ok(response);
    }
}
