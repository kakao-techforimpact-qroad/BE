package com.qroad.be.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "aws.s3")
public class AwsS3Properties {
    private String bucket;
    private String region;
    private Integer presignExpirationMinutes = 10;
}
