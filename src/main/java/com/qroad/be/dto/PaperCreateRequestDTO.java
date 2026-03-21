package com.qroad.be.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperCreateRequestDTO {
    private String title;
    private String tempKey; // "temp/{uuid}.pdf" — S3 임시 키
    private LocalDate publishedDate;
}
