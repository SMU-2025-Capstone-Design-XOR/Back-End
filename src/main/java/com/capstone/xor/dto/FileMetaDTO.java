package com.capstone.xor.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FileMetaDTO {
    private Long fileId; // 파일 id
    private String originalName; // 파일명
    private String relativePath; // 동기화 폴더 내 파일 위치
    private Long size; // 파일크기
    private String hash; // 파일 내용 해시(SHA-256)
    private String mimeType; // 파일 타입
    private String createdAt; // 생성 시간
    private String lastModified; // 최종 수정일(ISO 8601)
    private String lastSyncTime; // 마지막으로 동기화된 시간
}
