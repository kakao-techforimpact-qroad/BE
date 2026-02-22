package com.qroad.be.service;

import com.qroad.be.config.AwsS3Properties;
import com.qroad.be.dto.FinalizeUploadResponse;
import com.qroad.be.dto.PresignedUploadResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
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
        private final S3Client s3Client;

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

        public FinalizeUploadResponse finalizePdfUpload(String tempKey, Long publicationId) {
                String finalKey = "paper/" + publicationId + ".pdf";

                CopyObjectRequest copyRequest = CopyObjectRequest.builder()
                                .sourceBucket(properties.getBucket())
                                .sourceKey(tempKey)
                                .destinationBucket(properties.getBucket())
                                .destinationKey(finalKey)
                                .contentType("application/pdf")
                                .build();

                s3Client.copyObject(copyRequest);

                DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                                .bucket(properties.getBucket())
                                .key(tempKey)
                                .build();

                s3Client.deleteObject(deleteRequest);

                return new FinalizeUploadResponse(finalKey);
        }

        /**
         * S3의 tempKey 경로에 있는 PDF 파일을 바이트 배열로 읽어옵니다.
         */
        public byte[] readPdfBytes(String key) {
                GetObjectRequest getRequest = GetObjectRequest.builder()
                                .bucket(properties.getBucket())
                                .key(key)
                                .build();
                return s3Client.getObjectAsBytes(getRequest).asByteArray();
        }
}
