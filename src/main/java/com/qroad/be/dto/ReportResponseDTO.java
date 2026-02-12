package com.qroad.be.dto;

import com.qroad.be.domain.ReportEntity;
import lombok.Builder;
import lombok.Getter;

import java.time.ZonedDateTime;

@Getter
@Builder
public class ReportResponseDTO {

    private Long id;
    private String title;
    private String content;
    private String reporterContact;
    private String status;
    private ZonedDateTime createdAt;

    public static ReportResponseDTO from(ReportEntity entity) {
        return ReportResponseDTO.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .content(entity.getContent())
                .reporterContact(entity.getReporterContact())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
