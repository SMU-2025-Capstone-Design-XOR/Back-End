package com.capstone.xor.service;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.capstone.xor.dto.SyncFolderResponse;
import com.capstone.xor.entity.FileMeta;
import com.capstone.xor.entity.SyncFolder;
import com.capstone.xor.entity.User;
import com.capstone.xor.repository.FileMetaRepository;
import com.capstone.xor.repository.SyncFolderRepository;
import com.capstone.xor.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SyncFolderService {

    private final SyncFolderRepository syncFolderRepository;
    private final UserRepository userRepository;
    private final FileMetaRepository fileMetaRepository;
    private final AmazonS3 amazonS3;
    private final FileService fileService;

    @Value("${cloud.aws.s3.bucket}")
    private String bucketName;

    // 싱크 폴더 저장
    public SyncFolderResponse saveSyncFolder(Long userId, String folderPath) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자 입니다."));
        if (syncFolderRepository.existsByUserIdAndFolderPath(userId, folderPath)){
            throw new IllegalArgumentException("이미 존재하는 폴더 경로입니다.");
        }

        SyncFolder syncFolder = new SyncFolder();
        syncFolder.setUser(user);
        syncFolder.setFolderPath(folderPath);
        SyncFolder savedSyncFolder = syncFolderRepository.save(syncFolder);

        return new SyncFolderResponse(savedSyncFolder);
    }

    // 특정 유저의 모든 싱크 폴더 조회
    public List<SyncFolderResponse> getFoldersByUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자 입니다."));

        return syncFolderRepository.findByUser(user).stream()
                .map(SyncFolderResponse::new) // 각 SyncFolder 엔티티를 SyncFolderDTO로 변환
                .toList();
    }


    // 특정 싱크 폴더 삭제 (삭제시 db와 s3모두 고려하도록 수정함)
    @Transactional
    public void deleteSyncFolder(Long userId, Long folderId) {
        // 폴더 존재 및 권한 확인
        SyncFolder folder = syncFolderRepository.findById(folderId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 폴더 입니다."));

        if (!folder.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("이 폴더는 해당 사용자의 싱크 폴더가 아닙니다.");
        }

        // 폴더 내 모든 파일 메타데이터 조회
        List<FileMeta> files = fileMetaRepository.findBySyncFolderId(folderId);

        // S3에서 파일 삭제
        for (FileMeta file : files) {
            String s3Key = file.getS3Key();
            int latestIdx = s3Key.indexOf("/latest/");
            if (latestIdx == -1) {
                System.err.println("S3 키 형식이 잘못되었습니다: " + s3Key);
                continue;
            }
            String basePrefix = s3Key.substring(0, latestIdx);
            fileService.deleteAllS3ObjectsWithPrefix(basePrefix + "/");
        }

        // db에서 메타데이터 삭제
        fileMetaRepository.deleteAllBySyncFolderId(folderId);

        // 싱크 폴더 자체 삭제
        syncFolderRepository.delete(folder);

        System.out.println("싱크폴더 삭제 완료: " + folder.getFolderPath());
    }
}
