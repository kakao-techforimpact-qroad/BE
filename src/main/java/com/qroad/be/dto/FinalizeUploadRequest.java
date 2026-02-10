package com.qroad.be.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class FinalizeUploadRequest {
    private String tempKey;
    private Long publicationId;
}
