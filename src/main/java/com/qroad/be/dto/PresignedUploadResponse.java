package com.qroad.be.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PresignedUploadResponse {
    private String uploadUrl;
    private String tempKey;
}
