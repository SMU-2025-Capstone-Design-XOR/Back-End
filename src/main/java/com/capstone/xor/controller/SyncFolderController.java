package com.capstone.xor.controller;

import com.capstone.xor.dto.SyncFolderDTO;
import com.capstone.xor.service.SyncFolderService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users/{userId}/sync-folders")
@RequiredArgsConstructor
public class SyncFolderController {

    private final SyncFolderService syncFolderService;

    @PostMapping // 싱크 폴더 추가
    public ResponseEntity<SyncFolderDTO> saveSyncFolder(@PathVariable Long userId, @RequestBody SyncFolderRequest request) {
        SyncFolderDTO savedSyncFolder = syncFolderService.saveSyncFolder(userId, request.getFolderPath());
        return ResponseEntity.status(201).body(savedSyncFolder);
    }

    @GetMapping // 특정 유저의 모든 싱크 폴더 조회
    public ResponseEntity<List<SyncFolderDTO>> getFoldersByUserId(@PathVariable Long userId) {
        List<SyncFolderDTO> folderDTOs = syncFolderService.getFoldersByUser(userId);
        return ResponseEntity.ok(folderDTOs);
    }

    @DeleteMapping("/{folderId}")
    public ResponseEntity<String> deleteSyncFolder(@PathVariable Long userId, @PathVariable Long folderId) {
        syncFolderService.deleteSyncFolder(userId, folderId);
        return ResponseEntity.ok("폴더가 성공적으로 삭제되었습니다.");
    }

    @Setter
    @Getter
    public static class SyncFolderRequest {
        private String folderPath;

    }
}
