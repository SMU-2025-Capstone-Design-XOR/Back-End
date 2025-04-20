package com.capstone.xor.service;

import com.capstone.xor.dto.SyncFolderDTO;
import com.capstone.xor.entity.SyncFolder;
import com.capstone.xor.entity.User;
import com.capstone.xor.repository.SyncFolderRepository;
import com.capstone.xor.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.nio.file.AccessDeniedException;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SyncFolderService {

    private final SyncFolderRepository syncFolderRepository;
    private final UserRepository userRepository;

    // 싱크 폴더 저장
    public SyncFolderDTO saveSyncFolder(Long userId, String folderPath) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자 입니다."));
        if (syncFolderRepository.existsByUserIdAndFolderPath(userId, folderPath)){
            throw new IllegalArgumentException("이미 존재하는 폴더 경로입니다.");
        }

        SyncFolder syncFolder = new SyncFolder();
        syncFolder.setUser(user);
        syncFolder.setFolderPath(folderPath);
        SyncFolder savedSyncFolder = syncFolderRepository.save(syncFolder);

        return new SyncFolderDTO(savedSyncFolder);
    }

    // 특정 유저의 모든 싱크 폴더 조회
    public List<SyncFolderDTO> getFoldersByUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자 입니다."));

        return syncFolderRepository.findByUser(user).stream()
                .map(SyncFolderDTO::new) // 각 SyncFolder 엔티티를 SyncFolderDTO로 변환
                .toList();
    }


    // 특정 싱크 폴더 삭제
    public void deleteSyncFolder(Long userId, Long folderId) {
        SyncFolder folder = syncFolderRepository.findById(folderId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 폴더 입니다."));

        if (!folder.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("이 폴더는 해당 사용자의 싱크 폴더가 아닙니다.");
        }

        syncFolderRepository.delete(folder);
    }
}
