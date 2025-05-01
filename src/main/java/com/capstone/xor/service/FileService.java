package com.capstone.xor.service;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.capstone.xor.dto.FileMetaDTO;
import com.capstone.xor.dto.SyncRequest;
import com.capstone.xor.dto.SyncResult;
import com.capstone.xor.entity.FileMeta;
import com.capstone.xor.entity.SyncFolder;
import com.capstone.xor.entity.User;
import com.capstone.xor.exception.ResourceNotFoundException;
import com.capstone.xor.repository.FileMetaRepository;
import com.capstone.xor.repository.SyncFolderRepository;
import com.capstone.xor.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FileService {
    private final AmazonS3 amazonS3;
    private final SyncFolderRepository syncFolderRepository;
    private final FileMetaRepository fileMetaRepository;
    private final UserRepository userRepository;

    @Value("${cloud.aws.s3.bucket}")
    private String bucketName;

    /**
     * 사용자가 폴더에 접근 권한이 있는지 검증하는 메서드
     *
     * @param userId       사용자 ID
     * @param syncFolderId 폴더 ID
     * @throws AccessDeniedException     접근 권한이 없을 경우 발생
     * @throws ResourceNotFoundException 폴더를 찾을 수 없을 경우 발생
     */
    private void validateFolderAccess(Long userId, Long syncFolderId) {
        SyncFolder syncFolder = syncFolderRepository.findById(syncFolderId)
                .orElseThrow(() -> new ResourceNotFoundException("폴더를 찾을 수 없습니다: " + syncFolderId));
        // 사용자와 폴더의 소유자가 일치하는지 확인
        if (!syncFolder.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("해당 폴더에는 접근 권한이 없습니다: 사용자 ID" + userId);
        }
    }

    // 파일 업로드 메서드
    @Transactional
    public String uploadFile(Long userId, Long syncFolderId, MultipartFile file) {

        // 폴더 접근 권한 검증
        validateFolderAccess(userId, syncFolderId);

        try {
            // 파일 경로 생성: users/{userId}/{s3FolderId}/{fileName}
            String fileName = file.getOriginalFilename();
            String filePath = String.format("users/%d/sync-folders/%d/%s", userId, syncFolderId, fileName);

            System.out.println("[디버그] 사용중인 S3 버킷 이름: [" + bucketName + "]");

            // 파일 해시값 계산 (SHA-256)
            String hash = calculateFileHash(file.getInputStream());

            // S3에 파일 업로드
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(file.getContentType());
            metadata.setContentLength(file.getSize());

            amazonS3.putObject(bucketName, filePath, file.getInputStream(), metadata);

            // DB에 파일 메타데이터 저장
            // 연관 엔티티 조회(JPA getReferenceById 사용 시 실제 Select 쿼리는 필요 시점에만 나감)
            User user = userRepository.getReferenceById(userId);
            SyncFolder syncFolder = syncFolderRepository.getReferenceById(syncFolderId);

            FileMeta fileMeta = new FileMeta();
            fileMeta.setOriginalName(fileName);
            fileMeta.setS3Key(filePath);
            fileMeta.setSize(file.getSize());
            fileMeta.setMimeType(file.getContentType());
            fileMeta.setHash(hash);
            fileMeta.setUser(user);
            fileMeta.setSyncFolder(syncFolder);
            fileMeta.setLastModified(LocalDateTime.now());
            fileMeta.setLastSyncTime(LocalDateTime.now());

            fileMetaRepository.save(fileMeta);

            return filePath;
        } catch (IOException e) {
            throw new RuntimeException("파일 업로드 실패", e);
        }
    }

    /**
     * 파일의 SHA-256 해시값을 계산하는 메서드
     *
     * @param inputStream 파일 입력 스트림
     * @return SHA-256 해시값
     */
    private String calculateFileHash(InputStream inputStream) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;

            while ((read = inputStream.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }

            byte[] hash = digest.digest();

            // 바이트 배열을 16진수 문자열로 변환
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 알고리즘을 찾을 수 없습니다.", e);
        } finally {
            // 스트림을 닫지 않고 리셋( MultipartFile에서 다시 스트림을 열 수 있도록)
            if (inputStream.markSupported()) {
                inputStream.reset();
            }
        }
    }

    @Transactional(readOnly = true)
    public Resource downloadFileWithValidation(Long userId, Long syncFolderId, String fileName) {
        // 폴더 접근 권한 검증
        validateFolderAccess(userId, syncFolderId);

        // S3 키 생성(디코딩 된 파일명 사용)
        String s3Key = String.format("users/%d/sync-folders/%d/%s", userId, syncFolderId, fileName);

        // 내부용 다운로드 메서드 호출
        return downloadFile(s3Key);
    }

    // 내부용 파일 다운로드 메서드
    private Resource downloadFile(String s3Key) {
        try {
            // S3에서 파일 가져오기
            S3Object s3Object = amazonS3.getObject(bucketName, s3Key);
            S3ObjectInputStream stream = s3Object.getObjectContent();

            // InputStreamResource로 변환하여 반환
            return new InputStreamResource(stream);
        } catch (AmazonS3Exception e) {
            if(e.getStatusCode() == 404) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "파일을 찾을 수 없습니다: " + s3Key, e);
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "파일 다운로드 실패" + s3Key, e);
        }
    }

    /**
     * 폴더 동기화 메서드 - 클라이언트 파일과 서버 파일을 비교하여 동기화 결과 반환
     *
     * @param userId   사용자 ID
     * @param folderId 폴더 ID
     * @param request  동기화 요청 데이터
     * @return 동기화 결과
     */
    @Transactional
    public SyncResult syncFiles(Long userId, Long folderId, SyncRequest request) {
        // 폴더 접근 권한 검증
        validateFolderAccess(userId, folderId);

        // 서버에 저장된 파일 목록 조회
        List<FileMeta> serverFiles = fileMetaRepository.findBySyncFolderId(folderId);

        // 클라이언트 파일 이름 -> 메타데이터 맵 생성
        Map<String, FileMetaDTO> clientFileMap = request.getClientFiles().stream()
                .collect(Collectors.toMap(FileMetaDTO::getOriginalName, file -> file));

        // 서버 파일 이름 -> 메타데이터 맵 생성
        Map<String, FileMeta> serverFileMap = serverFiles.stream()
                .collect(Collectors.toMap(FileMeta::getOriginalName, file -> file));

        // 삭제 요청 처리
        if (request.getDeletedFileIds() != null && !request.getDeletedFileIds().isEmpty()) {
            processDeletedFiles(request.getDeletedFileIds());
        }

        // 업로드 대상 파일 식별 ( 클라이언트에는 있지만 서버에 없거나, 해시가 다른 파일)
        List<String> filesToUpload = new ArrayList<>();
        for (FileMetaDTO clientFile : request.getClientFiles()) {
            FileMeta serverFile = serverFileMap.get(clientFile.getOriginalName());
            if (serverFile == null) {
                // 서버에 없는 새 파일은 업로드 대상
                filesToUpload.add(clientFile.getOriginalName());
            } else if (!Objects.equals(clientFile.getHash(), serverFile.getHash())) {
                // 해시가 다른 파일(내용이 변경됨)은 업로드 대상

                // 클라이언트의 lastModified 시간 파싱
                LocalDateTime clientLastModified = LocalDateTime.parse(
                        clientFile.getLastModified(),
                        DateTimeFormatter.ISO_DATE_TIME);

                // 클라이언트의 lastModified가 서버보다 최신이면 업로드 대상에 추가
                if (clientLastModified.isAfter(serverFile.getLastModified())) {
                    filesToUpload.add(clientFile.getOriginalName());

                    // 동기화 시간 업데이트
                    serverFile.setLastSyncTime(LocalDateTime.now());
                    fileMetaRepository.save(serverFile);
                }
            }
        }

        // 다운로드 대상 파일 식별(서버에는 있지만 클라이언트에 없는 파일)
        List<String> filesToDownload = new ArrayList<>();
        for (FileMeta serverFile : serverFiles) {
            if (!clientFileMap.containsKey(serverFile.getOriginalName()) && (request.getDeletedFileIds() == null || !request.getDeletedFileIds().contains(serverFile.getId()))) {
                filesToDownload.add(serverFile.getOriginalName());
            }
        }

        // 충돌 파일 식별
        List<String> conflictFiles = new ArrayList<>();
        // 나중에 충돌로직 구현
        // 버전 관리를 위해서 구현해야함 현재는 그냥 덮어쓰기
        // 변경된 파일을 감지하는 로직 추가 필요

        // 서버 파일 메타데이터 토큰 DTO로 변환
        List<FileMetaDTO> serverFilesDTOs = serverFiles.stream()
                .map(file -> convertToDTO(file, userId, file.getSyncFolder().getId()))
                .collect(Collectors.toList());

        // 결과 반환
        SyncResult result = new SyncResult();
        result.setServerFiles(serverFilesDTOs);
        result.setFilesToUpload(filesToUpload);
        result.setFilesToDownload(filesToDownload);
        result.setConflictFiles(conflictFiles);

        return result;
    }

    /**
     * 삭제 요청된 파일들을 처리하는 메서드
     *
     * @param deletedFileIds 삭제할 파일 ID 목록
     */
    private void processDeletedFiles(List<Long> deletedFileIds) {
        for (Long fileId : deletedFileIds) {
            FileMeta file = fileMetaRepository.findById(fileId)
                    .orElseThrow(() -> new ResourceNotFoundException("파일을 찾을 수 없습니다: " + fileId));

            // S3에서 파일 삭제
            amazonS3.deleteObject(bucketName, file.getS3Key());

            // DB에서 메타데이터 삭제
            fileMetaRepository.delete(file);
        }
    }

    /**
     * 파일 삭제 메서드
     *
     * @param userId   사용자 ID
     * @param folderId 폴더 ID
     * @param fileId   파일 ID
     * @throws ResourceNotFoundException 파일을 찾을 수 없는 경우
     * @throws AccessDeniedException     접근 권한이 없는 경우
     */
    @Transactional
    public void deleteFile(Long userId, Long folderId, Long fileId) {
        // 파일 메타 데이터 조회
        FileMeta fileMeta = fileMetaRepository.findById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("파일을 찾을 수 없습니다: " + fileId));

        // 권한 검증 (파일의 사용자 id와 폴더 id가 요청과 일치하는지 검사
        if (!fileMeta.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("이 파일에 대한 접근 권한이 없습니다.");
        }

        if (!fileMeta.getSyncFolder().getId().equals(folderId)) {
            throw new AccessDeniedException("잘못된 폴더 접근입니다.");
        }

        // S3에 파일이 존재하는지 확인
        boolean objectExists = true;
        try {
            amazonS3.getObjectMetadata(bucketName, fileMeta.getS3Key());
        } catch (AmazonS3Exception e) {
            if (e.getStatusCode() == 404) {
                // 파일이 S3에 없는경우
                objectExists = false;
                System.out.println("파일이 S3에 존재하지 않습니다. DB에서만 삭제합니다: " + fileMeta.getOriginalName());
            }
        }

        // S3에 파일이 없으면 DB에서만 삭제
        if (!objectExists) {
           fileMetaRepository.delete(fileMeta);
            return;
        }

        // S3에 파일이 있으면 삭제 시도
        try {
            amazonS3.deleteObject(bucketName, fileMeta.getS3Key());
            // S3에서 삭제 성공시 DB에서도 삭제
            fileMetaRepository.delete(fileMeta);
            System.out.println(String.format("[%s] 파일 삭제 완료", fileMeta.getOriginalName()));
        } catch (AmazonS3Exception e) {
            // S3 삭제 실패 시 DB에서 삭제하지 않음
            System.err.println("S3 파일 삭제 실패: " + e.getMessage());
            throw new RuntimeException("파일 삭제 중 오류가 발생하였습니다: " + e.getMessage());
        }
    }

    /**
     * 특정 파일의 메타데이터 조회
     *
     * @param userId   사용자 ID
     * @param folderId 폴더 ID
     * @param fileName 파일 이름
     * @return 파일 메타데이터
     */
    @Transactional(readOnly = true)
    public FileMetaDTO getFileMetadata(Long userId, Long folderId, String fileName) {
        // 폴더 접근 권한 검증
        validateFolderAccess(userId, folderId);

        // 파일 메타 데이터 조회
        FileMeta fileMeta = fileMetaRepository.findByUserIdAndSyncFolderIdAndOriginalName(userId, folderId, fileName)
                .orElseThrow(() -> new ResourceNotFoundException("파일을 찾을 수 없습니다: " + fileName));

        // 엔티티를 dto로 변환하여 반환
        return convertToDTO(fileMeta, userId, folderId);
    }

    /**
     * FileMeta 엔티티를 FileMetaDTO로 변환하는 메서드
     * @param fileMeta 파일 메타데이터 엔티티
     * @return 파일 메타데이터 DTO
     */
    private FileMetaDTO convertToDTO(FileMeta fileMeta, Long userId, Long syncFolderId) {
        FileMetaDTO dto = new FileMetaDTO();
        dto.setOriginalName(fileMeta.getOriginalName());
        dto.setSize(fileMeta.getSize());
        dto.setMimeType(fileMeta.getMimeType());

        // 해시값 설정
        dto.setHash(fileMeta.getHash());

        // 상대 경로 설정(s3키에서 추출 또는 별도의 필드 "" 사용)
        String s3Key = fileMeta.getS3Key();
        String prefix = String.format("users/%d/sync-folders/%d/", userId, syncFolderId);
        if (s3Key.startsWith(prefix)) {
            // 접두사 이후의 모든 경로를 상대경로로 사용
            dto.setRelativePath(s3Key.substring(prefix.length()));
        } else {
            dto.setRelativePath("");
        }

        // 파일 id 설정
        if (fileMeta.getId() != null) {
            dto.setFileId(fileMeta.getId());
        }

        // 생성 시간 설정
        if (fileMeta.getCreatedAt() != null) {
            dto.setCreatedAt(fileMeta.getCreatedAt().format(DateTimeFormatter.ISO_DATE_TIME));
        }

        // 마지막 수정일 설정
        if (fileMeta.getLastModified() != null) {
            dto.setLastModified(fileMeta.getLastModified().format(DateTimeFormatter.ISO_DATE_TIME));
        }

        // 마지막 동기화 시간 설정
        if (fileMeta.getLastSyncTime() != null) {
            dto.setLastSyncTime(fileMeta.getLastSyncTime().format(DateTimeFormatter.ISO_DATE_TIME));
        }

        return dto;
    }

    /**
     * 특정 사용자와 폴더의 파일 메타데이터 조회
     *
     * @param userId       사용자 ID
     * @param syncFolderId 폴더 ID
     * @return 파일 메타데이터 목록
     */
    @Transactional(readOnly = true)
    public List<FileMetaDTO> getFilesByUserAndFolder(Long userId, Long syncFolderId) {
        // 폴더 접근 권한 검증
        validateFolderAccess(userId, syncFolderId);

        // 사용자와 폴더 id로 파일 조회
        List<FileMeta> files = fileMetaRepository.findByUserIdAndSyncFolderId(userId, syncFolderId);

        // 엔티티를 dto로 변환하여 반환
        return files.stream()
                .map(file -> convertToDTO(file, userId, syncFolderId))
                .collect(Collectors.toList());
    }

    /**
     * 파일명으로 파일 검색
     *
     * @param userId  사용자 ID (권한 검증용)
     * @param keyword 검색 키워드
     * @return 검색된 파일 메타데이터 목록
     */
    @Transactional(readOnly = true)
    public List<FileMetaDTO> searchFilesByName(Long userId, String keyword) {
        // 사용자 존재 여부 확인
        userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자를 찾을 수 없습니다: " + userId));
        // 파일명으로 검색
        List<FileMeta> files = fileMetaRepository.findByOriginalNameContaining(keyword);

        // 사용자가 접근 가능한 파일만 필터링 (사용자의 파일만 반환)
        List<FileMeta> accessibleFiles = files.stream()
                .filter(file -> file.getUser().getId().equals(userId))
                .collect(Collectors.toList());

        // 엔티티를 dto로 변환하여 반환
        return accessibleFiles.stream()
                .map(file -> convertToDTO(file, file.getUser().getId(), file.getSyncFolder().getId()))
                .collect(Collectors.toList());
    }

}
