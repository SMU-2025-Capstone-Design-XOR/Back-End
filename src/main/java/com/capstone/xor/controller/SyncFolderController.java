package com.capstone.xor.controller;

import com.capstone.xor.dto.SyncFolderResponse;
import com.capstone.xor.dto.SyncRequest;
import com.capstone.xor.dto.SyncResult;
import com.capstone.xor.service.FileService;
import com.capstone.xor.service.SyncFolderService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users/{userId}/sync-folders")
@RequiredArgsConstructor
public class SyncFolderController {

    private final SyncFolderService syncFolderService;
    private final FileService fileService;

    @PostMapping // 싱크 폴더 추가
    public ResponseEntity<SyncFolderResponse> saveSyncFolder(@PathVariable Long userId, @RequestBody SyncFolderRequest request, Authentication authentication) {
        // jwt 인증 검증
        Long authenticatedUserId = (Long) authentication.getDetails();
        if (!userId.equals(authenticatedUserId)) {
            throw new AccessDeniedException("접근 권한이 없습니다.");
        }

        SyncFolderResponse savedSyncFolder = syncFolderService.saveSyncFolder(userId, request.getFolderPath());
        return ResponseEntity.status(201).body(savedSyncFolder);
    }

    @GetMapping // 특정 유저의 모든 싱크 폴더 조회
    public ResponseEntity<List<SyncFolderResponse>> getFoldersByUserId(@PathVariable Long userId, Authentication authentication) {
        // jwt 인증 검증
        Long authenticatedUserId = (Long) authentication.getDetails();
        if (!userId.equals(authenticatedUserId)) {
            throw new AccessDeniedException("접근 권한이 없습니다.");
        }
        List<SyncFolderResponse> folderDTOs = syncFolderService.getFoldersByUser(userId);
        return ResponseEntity.ok(folderDTOs);
    }

    @DeleteMapping("/{folderId}") // 싱크 폴더 삭제
    public ResponseEntity<String> deleteSyncFolder(@PathVariable Long userId, @PathVariable Long folderId, Authentication authentication) {
        // jwt 인증 검증
        Long authenticatedUserId = (Long) authentication.getDetails();
        if (!userId.equals(authenticatedUserId)) {
            throw new AccessDeniedException("접근 권한이 없습니다.");
        }

        syncFolderService.deleteSyncFolder(userId, folderId);
        return ResponseEntity.ok("폴더가 성공적으로 삭제되었습니다.");
    }

    /**
     * 폴더 동기화 엔드포인트
     * @param userId 사용자 ID
     * @param folderId 폴더 ID
     * @param request 동기화 요청 데이터
     * @param authentication 인증 정보
     * @return 동기화 결과
     */
    @PostMapping("/{folderId}/sync")
    public ResponseEntity<SyncResult> syncFolder(
            @PathVariable Long userId,
            @PathVariable Long folderId,
            @RequestBody SyncRequest request,
            Authentication authentication)
    {
        // jwt 등 인증 처리
        Long authenticatedUserId = (Long) authentication.getDetails();
        if (!userId.equals(authenticatedUserId)) {
            throw new AccessDeniedException("접근 권한이 없습니다.");
        }
        SyncResult result = fileService.syncFiles(userId, folderId, request);
        return ResponseEntity.ok(result);
    }

    @Setter
    @Getter
    public static class SyncFolderRequest {
        private String folderPath;

    }
}
