package com.capstone.xor.repository;

import com.capstone.xor.entity.FileMeta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileMetaRepository extends JpaRepository<FileMeta, Long> {

    // 특정 폴더의 모든 파일 조회
    List<FileMeta> findBySyncFolderId(Long syncFolderId);
    boolean existsByOriginalNameAndSyncFolderId(String originalName, Long syncFolderId);

    // 특정 사용자와 폴더의 파일 조회
    List<FileMeta> findByUserIdAndSyncFolderId(Long userId, Long syncFolderId);

    // 파일명으로 검색
    @Query("SELECT f FROM FileMeta f WHERE f.user.id = :userId AND f.s3Key LIKE %:keyword%")
    List<FileMeta> findByUserIdAndS3KeyContaining(@Param("userId") Long userId, @Param("keyword") String keyword);

    // 사용자 id, 폴더 id, 상대경로로 메타데이터 조회
    Optional<FileMeta> findByUserIdAndSyncFolderIdAndS3Key(Long userId, Long syncFolderId, String S3Key);

    // 폴더 아이디로 그 폴더의 모든 파일 삭제
    void deleteAllBySyncFolderId(Long syncFolderId);
}
