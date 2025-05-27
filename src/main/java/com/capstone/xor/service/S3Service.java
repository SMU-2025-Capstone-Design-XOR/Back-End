package com.capstone.xor.service;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.util.Date;

@Service
@RequiredArgsConstructor
public class S3Service {

    private final AmazonS3 amazonS3;

    @Value("${cloud.aws.s3.bucket}")
    private String bucketName;

    // Presigned URL 생성 (다운로드용)
    public String generatePresignedUrl(String key, int expireMinutes) {
        Date expiration = new Date(System.currentTimeMillis() + 1000L * 60 * expireMinutes);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucketName, key)
                .withMethod(HttpMethod.GET)
                .withExpiration(expiration);
        URL url = amazonS3.generatePresignedUrl(request);
        return url.toString();
    }
}
