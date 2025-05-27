package com.capstone.xor.controller;

import com.capstone.xor.service.S3Service;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/s3")
@RequiredArgsConstructor
public class S3Controller {
    private final S3Service s3Service;

    // Presigned URL 발급 (다운로드)
    @GetMapping("/presigned-download")
    public String getPresignedDownloadUrl(
            @RequestParam("key") String key,
            @RequestParam(value = "expire", defaultValue = "15") int expireMinutes) {
        return s3Service.generatePresignedUrl(key, expireMinutes);
    }
}
