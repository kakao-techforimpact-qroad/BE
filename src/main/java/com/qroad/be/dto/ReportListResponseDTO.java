package com.qroad.be.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class ReportListResponseDTO {

    private long totalCount;
    private List<ReportResponseDTO> reports;
}
