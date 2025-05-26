package com.capstone.xor.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "files")
@Getter
@Setter
@NoArgsConstructor
public class FileMeta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 사용자가 올린 파일명
    @Column(nullable = false)
    private String originalName;

    // S3 저장 키(경로)
    @Column(length = 512)
    private String s3Key;

    // 파일 크기
    private Long size;

    // 파일 타임
    private String mimeType;

    // 파일 해시값
    @Column(length = 64) // SHA-256 해시는 64자
    private String hash;

    // 현재 파일의 버전 (최초 1로 시작)
    @Column(nullable = false)
    private int currentVersion = 1;

    // 업로드한 사용자
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 소속 폴더
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sync_folder_id", nullable = false)
    private SyncFolder syncFolder;

    // 생성 시간
    private LocalDateTime createdAt = LocalDateTime.now();

    // 마지막 수정 시간
    private LocalDateTime lastModified = LocalDateTime.now();

    // 마지막 동기화 시간
    private LocalDateTime lastSyncTime = LocalDateTime.now();
}
