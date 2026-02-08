package com.qroad.be.service;

import com.qroad.be.config.AwsS3Properties;
import com.qroad.be.dto.PresignedUploadResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class S3PresignService {

    private final S3Presigner presigner;
    private final AwsS3Properties properties;

    public PresignedUploadResponse createPdfUploadUrl() {
        String key = "temp/" + UUID.randomUUID() + ".pdf";

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .contentType("application/pdf")
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(properties.getPresignExpirationMinutes()))
                .putObjectRequest(putObjectRequest)
                .build();

        PresignedPutObjectRequest presignedRequest = presigner.presignPutObject(presignRequest);

        return new PresignedUploadResponse(presignedRequest.url().toString(), key);
    }
}
