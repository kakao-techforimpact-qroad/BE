package com.qroad.be.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ReportCreateRequestDTO {

    private String title;
    private String content;
    private String reporterContact;
}
