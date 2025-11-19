package com.qroad.be.domain.paper;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class QrCodeResponse {

    @JsonProperty("qr_key")
    private String qrKey;

    @JsonProperty("qr_image_url")
    private String qrImageUrl;

    @JsonProperty("target_url")
    private String targetUrl;
}