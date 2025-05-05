package com.capstone.xor.controller;

import com.capstone.xor.dto.FileMetaDTO;
import com.capstone.xor.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class FileController {
    private final FileService fileService;

    // 파일 업로드 엔드포인트
    @PostMapping("/users/{userId}/sync-folders/{folderId}/upload")
    public ResponseEntity<Map<String, String>> uploadFile(
            @PathVariable Long userId,
            @PathVariable Long folderId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "relativePath", required = false, defaultValue = "") String relativePath,
            Authentication authentication) {

        // 유효한 경로인지 검증
        if (relativePath.contains("..") || relativePath.contains("//") || relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            throw new IllegalArgumentException("잘못된 경로 형식입니다.");
        }

        // jwt에서 추출한 userid와 url의 userid비교로 접근 권한 검증
        Long authenticatedUserId = (Long)authentication.getDetails();
        if (!userId.equals(authenticatedUserId)) {
            throw new AccessDeniedException("접근 권한이 없습니다.");
        }

        // 파일 업로드 처리
        String filePath = fileService.uploadFile(userId, folderId, file, relativePath);

        // 성공 응답 반환
        Map<String, String> response = new HashMap<>();
        response.put("filePath", filePath);
        response.put("message", "파일 업로드 성공");

        return ResponseEntity.ok(response);
    }

    // 파일 다운로드 엔드포인트
    @GetMapping("/users/{userId}/sync-folders/{folderId}/files/{relativePath}")
    public ResponseEntity<Resource> downloadFile(
            @PathVariable Long userId,
            @PathVariable Long folderId,
            @PathVariable String relativePath,
            Authentication authentication) {

        // jwt에서 추출한 userid와 url의 userid를 비교
        Long authenticatedUserId = (Long) authentication.getDetails();
        if (!userId.equals(authenticatedUserId)) {
            throw new AccessDeniedException("접근 권한이 없습니다.");
        }

        // url 디코딩
        String decodedRelativePath = URLDecoder.decode(relativePath, StandardCharsets.UTF_8);

        // 경로 생성과 검증을 함께 처리하는 메서드 호출
        Resource resource = fileService.downloadFileWithValidation(userId, folderId, decodedRelativePath);

        // RFC 5987 규약에 따른 한글 파일명 인코딩 처리
        String encodedFilename = encodeFilename(decodedRelativePath);

        // Content-Disposition 헤더 설정
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, encodedFilename)
                .body(resource);
    }

    // 특정 사용자와 폴더의 파일 목록 조회
    @GetMapping("/users/{userId}/sync-folders/{folderId}/files")
    public ResponseEntity<List<FileMetaDTO>> getFilesByUserAndFolder(
            @PathVariable Long userId,
            @PathVariable Long folderId,
            Authentication authentication) {

        // jwt 인증 검증
        Long authenticatedUserId = (Long) authentication.getDetails();
        if (!userId.equals(authenticatedUserId)) {
            throw new AccessDeniedException("접근 권한이 없습니다.");
        }

        List<FileMetaDTO> files = fileService.getFilesByUserAndFolder(userId, folderId);
        return ResponseEntity.ok(files);
    }

    // 파일명으로 파일 검색
    @GetMapping("/users/{userId}/files/search")
    public ResponseEntity<List<FileMetaDTO>> searchFiles(
            @PathVariable Long userId,
            @RequestParam String keyword,
            Authentication authentication) {

        // jwt인증 검증
        Long authenticatedUserId = (Long) authentication.getDetails();
        if (!userId.equals(authenticatedUserId)) {
            throw new AccessDeniedException("접근 권한이 없습니다.");
        }

        List<FileMetaDTO> files = fileService.searchFilesByName(userId, keyword);
        return ResponseEntity.ok(files);
    }

    /**
     * 특정 파일의 메타데이터 조회
     * @param userId 사용자 ID
     * @param folderId 폴더 ID
     * @param relativePath 파일 이름
     * @param authentication 인증 정보
     * @return 파일 메타데이터
     */
    @GetMapping("/users/{userId}/sync-folders/{folderId}/files/{relativePath}/meta")
    public ResponseEntity<FileMetaDTO> getFileMeta(
            @PathVariable Long userId,
            @PathVariable Long folderId,
            @PathVariable String relativePath,
            Authentication authentication) {

        // URL 디코딩
        relativePath = URLDecoder.decode(relativePath, StandardCharsets.UTF_8);

        // jwt 인증 검증
        Long authenticatedUserId = (Long) authentication.getDetails();
        if (!userId.equals(authenticatedUserId)) {
            throw new AccessDeniedException("접근 권한이 없습니다.");
        }

        // 파일 메타 데이터 조회
        FileMetaDTO fileMetaDTO = fileService.getFileMetadata(userId, folderId, relativePath);
        return ResponseEntity.ok(fileMetaDTO);
    }

    /**
     * 특정 파일 삭제
     *
     * @param userId         사용자 ID
     * @param folderId       폴더 ID
     * @param fileId         파일 ID
     * @param authentication 인증 정보
     * @return 삭제 결과 메시지
     */
    @DeleteMapping("/users/{userId}/sync-folders/{folderId}/files/{fileId}")
    public ResponseEntity<Map<String, String>> deleteFile(
            @PathVariable Long userId,
            @PathVariable Long folderId,
            @PathVariable Long fileId,
            Authentication authentication) {

        // jwt검증 인증
        Long authenticatedUserId = (Long) authentication.getDetails();
        if (!userId.equals(authenticatedUserId)) {
            throw new AccessDeniedException("접근 권한이 없습니다.");
        }

        // 파일 삭제 서비스 호출
        fileService.deleteFile(userId, folderId, fileId);

        // 성공 응답 반환
        Map<String, String> response = new HashMap<>();
        response.put("message", " 파일이 성공적으로 삭제되었습니다.");

        return ResponseEntity.ok(response);
    }

    // RFC 5987 규약에 따른 파일명 인코딩 메서드 추가
    private String encodeFilename(String filename) {
        // url 인코딩 처리
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8)
                .replace("+", "%20"); // 공백 처리

        // RFC 5987 형식으로 반환
        // 1. 일반 ASCII 형식
        // 2. UTF-8 인코딩 형식
        return String.format("attachment; filename=\"%s\";filename*=UTF-8''%s", encodedFilename, encodedFilename);
    }
}
