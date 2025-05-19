package com.capstone.xor.repository;

import com.capstone.xor.entity.FileMeta;
import com.capstone.xor.entity.VersionMetadata;
import com.capstone.xor.entity.VersionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VersionMetadataRepository extends JpaRepository<VersionMetadata, Long> {
//    // 최신 버전(가장 큰 versionNumber)을 반환
//    Optional<VersionMetadata> findTopByFileMetaOrderByVersionNumberDesc(FileMeta fileMeta);
//
//    // diff 개수 세기(병합 필요 여부 판단)
//    long countByFileMetaAndVersionType(FileMeta fileMeta, VersionType versionType);
//
//    // 특정 파일의 모든 버전 내림차순 조회
//    List<VersionMetadata> findByFileMetaOrderByVersionNumberDesc(FileMeta fileMeta);

    List<VersionMetadata> findByFileMeta_IdOrderByVersionNumberAsc(Long fileMetaId);
}
