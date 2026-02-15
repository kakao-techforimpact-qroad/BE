package com.qroad.be.dto;

import com.qroad.be.progress.PublicationJobStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicationProgressDto {
    private PublicationJobStatus status;
    private int progress;
    private String message;
    private Instant timestamp;
}
