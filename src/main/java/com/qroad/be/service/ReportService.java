package com.qroad.be.service;

import com.qroad.be.domain.ReportEntity;
import com.qroad.be.dto.ReportCreateRequestDTO;
import com.qroad.be.dto.ReportListResponseDTO;
import com.qroad.be.dto.ReportResponseDTO;
import com.qroad.be.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    private final ReportRepository reportRepository;

    /**
     * 제보 등록 (공개)
     */
    @Transactional
    public ReportResponseDTO createReport(ReportCreateRequestDTO request) {
        ReportEntity report = ReportEntity.builder()
                .title(request.getTitle())
                .content(request.getContent())
                .reporterContact(request.getReporterContact())
                .build();

        ReportEntity saved = reportRepository.save(report);
        log.info("제보 등록 완료: id={}, title={}", saved.getId(), saved.getTitle());

        return ReportResponseDTO.from(saved);
    }

    /**
     * 제보 목록 조회 (관리자)
     */
    public ReportListResponseDTO getReports(int page, int limit, String status) {
        Pageable pageable = PageRequest.of(page - 1, limit);

        Page<ReportEntity> reportPage;
        if (status != null && !status.isBlank()) {
            reportPage = reportRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        } else {
            reportPage = reportRepository.findAllByOrderByCreatedAtDesc(pageable);
        }

        List<ReportResponseDTO> reports = reportPage.getContent().stream()
                .map(ReportResponseDTO::from)
                .collect(Collectors.toList());

        return new ReportListResponseDTO(reportPage.getTotalElements(), reports);
    }

    /**
     * 제보 상세 조회 (관리자)
     */
    public ReportResponseDTO getReport(Long reportId) {
        ReportEntity report = reportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 제보입니다. id=" + reportId));

        return ReportResponseDTO.from(report);
    }
}
