package com.capstone.xor.dto;

import com.capstone.xor.entity.VersionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VersionDTO {
    private Long versionId; // VersionMetadata의 id
    private int versionNumber; // 버전 번호
    private VersionType versionType; // SNAPSHOT or DIFF
    private String s3Key; // S3 파일 위치
    private LocalDateTime createdDate; // 생성 일시
}
