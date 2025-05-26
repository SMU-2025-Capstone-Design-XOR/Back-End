package com.capstone.xor.service;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.capstone.xor.dto.DiffResult;
import com.capstone.xor.dto.FileMetaDTO;
import com.capstone.xor.dto.SyncRequest;
import com.capstone.xor.dto.SyncResult;
import com.capstone.xor.entity.*;
import com.capstone.xor.exception.ResourceNotFoundException;
import com.capstone.xor.repository.FileMetaRepository;
import com.capstone.xor.repository.SyncFolderRepository;
import com.capstone.xor.repository.UserRepository;
import com.capstone.xor.repository.VersionMetadataRepository;
import com.capstone.xor.util.FileUtil;
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

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FileService {
    private final AmazonS3 amazonS3;
    private final SyncFolderRepository syncFolderRepository;
    private final FileMetaRepository fileMetaRepository;
    private final UserRepository userRepository;
    private final VersionMetadataRepository versionMetadataRepository;
    private final FileUtil fileUtil;
    private final DiffService diffService;

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
    public String uploadFile(Long userId, Long syncFolderId, MultipartFile file, String relativePath) {
        validateFolderAccess(userId, syncFolderId);

        String fileName = file.getOriginalFilename();
        String extension = getExtension(fileName);
        System.out.println("[업로드] 파일명: " + fileName + ", 확장자: " + extension);

        // 파일별 S3 baseKey (파일명 기준 디렉터리)
        String baseKey = String.format("users/%d/sync-folders/%d/%s/%s", userId, syncFolderId, relativePath, fileName);

        // 최신 파일 S3 key (항상 최신본, 사용자 확인용)
        String latestKey = baseKey + "/latest/" + fileName;

        // 최초 파일 S3 key (복원에 사용 될 최초 버전)
        String snapshotKey = baseKey + "/.snapshot/" + fileName.replace("." + extension, "_v1." + extension);

        // filemeta 조회
        Optional<FileMeta> fileMetaOpt = fileMetaRepository.findByUserIdAndSyncFolderIdAndOriginalName(userId, syncFolderId, fileName);

        FileMeta fileMeta;
        Long fileId;

        if (fileMetaOpt.isEmpty()) {
            // 신규 파일 등록
            fileMeta = new FileMeta();
            fileMeta.setOriginalName(fileName);
            fileMeta.setUser(userRepository.getReferenceById(userId));
            fileMeta.setSyncFolder(syncFolderRepository.getReferenceById(syncFolderId));
            fileMeta.setCurrentVersion(1);
            fileMeta.setLastModified(LocalDateTime.now());
            fileMeta.setLastSyncTime(LocalDateTime.now());
            fileMeta.setSize(file.getSize());
            fileMeta.setMimeType(file.getContentType());
            try {
                fileMeta.setHash(calculateFileHash(file.getInputStream()));
            } catch (IOException e) {
                throw new RuntimeException("파일 해시 계산 중 오류", e);
            }
            fileMetaRepository.save(fileMeta);
            fileId = fileMeta.getId();

            // 1. snapshot 저장 (최초 버전만)
            try {
                fileUtil.uploadToS3(snapshotKey, file);
            } catch (IOException e) {
                throw new RuntimeException("S3 snapshot 업로드 중 오류 발생", e);
            }

            // 2. 최신 파일 위치에도 저장
            try {
                fileUtil.uploadToS3(latestKey, file);
            } catch (IOException e) {
                throw new RuntimeException("최신 파일 업로드 중 오류 발생", e);
            }

            fileMeta.setS3Key(latestKey); // 최신 파일의 위치로 저장
            fileMetaRepository.save(fileMeta);

            VersionMetadata versionMeta = VersionMetadata.builder()
                    .fileMeta(fileMeta)
                    .versionNumber(1)
                    .versionType(VersionType.SNAPSHOT)
                    .s3Key(snapshotKey)
                    .createdDate(LocalDateTime.now())
                    .build();
            versionMetadataRepository.save(versionMeta);

            System.out.println("[업로드] 신규 FileMeta 생성, fileId: " + fileId);
            System.out.println("[업로드] 최초 버전 등록 완료");
            return latestKey;
        } else {
            // 업데이트
            fileMeta = fileMetaOpt.get();
            fileId = fileMeta.getId();

            System.out.println("[업로드] 기존 FileMeta 조회, fileId: " + fileId + ", currentVersion: " + fileMeta.getCurrentVersion());
            System.out.println("[업로드] S3 baseKey: " + baseKey);

            int newVersion = fileMeta.getCurrentVersion() + 1;
            String tmpKey = baseKey + "/tmp/v" + newVersion + "." + extension;
            System.out.println("[업데이트] 임시 업로드 tmpKey: " + tmpKey);
            try {
                // 1. 새 파일을 임시로 업로드
                fileUtil.uploadToS3(tmpKey, file);

                // 2. 최신 파일(이전 버전)과 새 파일로 diff 계산
                File prevFile = fileUtil.downloadFromS3(latestKey);
                File newFile = fileUtil.downloadFromS3(tmpKey);

                boolean isPrevOOXML = fileUtil.isOOXMLFile(prevFile) && fileUtil.isZipFile(prevFile);
                boolean isNewOOXML  = fileUtil.isOOXMLFile(newFile)  && fileUtil.isZipFile(newFile);

                File prevUnzipDir;
                File newUnzipDir;

                if (isPrevOOXML && isNewOOXML) {
                    prevUnzipDir = fileUtil.unzipToTempDir(prevFile);
                    newUnzipDir  = fileUtil.unzipToTempDir(newFile);
                } else if (!isPrevOOXML && !isNewOOXML) {
                    prevUnzipDir = fileUtil.singleFileToTempDir(prevFile, fileName);
                    newUnzipDir  = fileUtil.singleFileToTempDir(newFile, fileName);
                } else {
                    fileUtil.deleteFromS3(tmpKey);
                    throw new RuntimeException("이전 버전과 새 버전의 파일 유형이 달라 비교할 수 없습니다.");
                }

                List<DiffResult> diffs = diffService.diffAllFiles(prevUnzipDir, newUnzipDir);
                if (diffs == null || diffs.isEmpty()) {
                    fileUtil.deleteFromS3(tmpKey);
                    throw new RuntimeException("diff 계산 결과가 없습니다. 기존 파일을 유지합니다.");
                }

                // 3. diff 파일 S3 저장, versionmetadata(diff) 생성
                for (DiffResult diff : diffs) {
                    String diffKey = baseKey + String.format("/.diffs/v%d/%s.diff", newVersion, diff.getRelativePath());
                    fileUtil.uploadToS3(diffKey, diff.toInputStream(), diff.getSize(), diff.getMimeType());

                    VersionMetadata versionMeta = VersionMetadata.builder()
                            .fileMeta(fileMeta)
                            .versionNumber(newVersion)
                            .versionType(VersionType.DIFF)
                            .s3Key(diffKey)
                            .createdDate(LocalDateTime.now())
                            .build();
                    versionMetadataRepository.save(versionMeta);
                }

                // 4. diff 저장 성공 후 최신 파일 교체
                fileUtil.copyInS3(tmpKey, latestKey); // S3 내에서 파일 복사(덮어쓰기)
                fileUtil.deleteFromS3(tmpKey);

                // 5. fileMeta 갱신
                fileMeta.setCurrentVersion(newVersion);
                fileMeta.setS3Key(latestKey); // 최신 파일의 위치로 갱신
                fileMeta.setSize(file.getSize());
                fileMeta.setMimeType(file.getContentType());
                try {
                    fileMeta.setHash(calculateFileHash(file.getInputStream()));
                } catch (IOException e) {
                    throw new RuntimeException("파일 해시 계산 중 오류", e);
                }
                fileMeta.setLastModified(LocalDateTime.now());
                fileMeta.setLastSyncTime(LocalDateTime.now());
                fileMetaRepository.save(fileMeta);

                System.out.println("[업데이트] 파일 메타데이터/버전메타데이터 갱신 완료");
                return latestKey;

            } catch (IOException e) {
                try { fileUtil.deleteFromS3(tmpKey); } catch (Exception ignore) {}
                System.out.println("[업데이트][에러] S3 업로드/처리 중 오류: " + e.getMessage());
                throw new RuntimeException("S3 업로드/처리 중 오류 발생", e);
            }
        }
    }

    // 파일 확장자 추출 함수 예시
    private String getExtension(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        return (dotIdx != -1) ? fileName.substring(dotIdx + 1) : "";
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
    public Resource downloadFileWithValidation(Long userId, Long syncFolderId, String relativePath) {
        // 폴더 접근 권한 검증
        validateFolderAccess(userId, syncFolderId);
        // 타임스탬프 포맷 지정
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        // relativePath 값 로그로 출력
        System.out.println("[" + now + "] downloadFileWithValidation - relativePath: " + relativePath);

        // S3 키 생성(디코딩 된 파일명 사용)
        String s3Key = String.format("users/%d/sync-folders/%d/%s", userId, syncFolderId, relativePath);

        System.out.println("[" + now + "] downloadFileWithValidation - s3Key: " + s3Key);

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
                .collect(Collectors.toMap(FileMetaDTO::getRelativePath, file -> file));

        // 서버 파일 이름 -> 메타데이터 맵 생성
        Map<String, FileMeta> serverFileMap = serverFiles.stream()
                .collect(Collectors.toMap(this::extractRelativePath, file -> file));

        // 삭제 요청 처리
        if (request.getDeletedFileIds() != null && !request.getDeletedFileIds().isEmpty()) {
            processDeletedFiles(request.getDeletedFileIds());
        }

        // 업로드 대상 파일 식별 ( 클라이언트에는 있지만 서버에 없거나, 해시가 다른 파일)
        List<String> filesToUpload = new ArrayList<>();
        for (FileMetaDTO clientFile : request.getClientFiles()) {
            FileMeta serverFile = serverFileMap.get(clientFile.getRelativePath());
            if (serverFile == null) {
                // 서버에 없는 새 파일은 업로드 대상
                filesToUpload.add(clientFile.getRelativePath());
            } else if (!Objects.equals(clientFile.getHash(), serverFile.getHash())) {
                // 해시가 다른 파일(내용이 변경됨)은 업로드 대상

                // 클라이언트의 lastModified 시간 파싱
                LocalDateTime clientLastModified = LocalDateTime.parse(
                        clientFile.getLastModified(),
                        DateTimeFormatter.ISO_DATE_TIME);

                // 클라이언트의 lastModified가 서버보다 최신이면 업로드 대상에 추가
                if (clientLastModified.isAfter(serverFile.getLastModified())) {

                    serverFile.setHash(clientFile.getHash()); // hash 갱신
                    serverFile.setSize(clientFile.getSize()); // file size 갱신
                    serverFile.setLastModified(clientLastModified); // lastModified 갱신
                    serverFile.setLastSyncTime(LocalDateTime.now()); // 동기화 시간 갱신
                    fileMetaRepository.save(serverFile); // DB에 반영(자동으로 해주지만 명확성을 위해 작성)
                    filesToUpload.add(clientFile.getRelativePath());
                }
            }
        }

        // 다운로드 대상 파일 식별(서버에는 있지만 클라이언트에 없는 파일)
        List<String> filesToDownload = new ArrayList<>();
        for (FileMeta serverFile : serverFiles) {
            String serverRelativePath = extractRelativePath(serverFile);
            if (!clientFileMap.containsKey(serverRelativePath) && (request.getDeletedFileIds() == null || !request.getDeletedFileIds().contains(serverFile.getId()))) {
                filesToDownload.add(serverRelativePath);
            }
        }

        // 충돌 파일 식별
        List<String> conflictFiles = new ArrayList<>();
        // 나중에 충돌로직 구현
        // 버전 관리를 위해서 구현해야함 현재는 그냥 덮어쓰기
        // 변경된 파일을 감지하는 로직 추가 필요

        // 서버 파일 메타데이터 토큰 DTO로 변환
        List<FileMetaDTO> serverFilesDTOs = serverFiles.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());

        // 결과 반환
        SyncResult result = new SyncResult();
        result.setServerFiles(serverFilesDTOs);
        result.setFilesToUpload(filesToUpload);
        result.setFilesToDownload(filesToDownload);
        result.setConflictFiles(conflictFiles);

        return result;
    }

    // 상대 경로 추출 메서드
    public String extractRelativePath(FileMeta file){
        String s3Key = file.getS3Key();
        String prefix = String.format("users/%d/sync-folders/%d/",file.getUser().getId(), file.getSyncFolder().getId());

        return s3Key.startsWith(prefix)
                ? s3Key.substring(prefix.length())
                : "";
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
            System.out.printf("[%s] 파일 삭제 완료", fileMeta.getOriginalName());
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
     * @param relativePath 파일의 상대경로
     * @return 파일 메타데이터
     */
    @Transactional(readOnly = true)
    public FileMetaDTO getFileMetadata(Long userId, Long folderId, String relativePath) {
        // 폴더 접근 권한 검증
        validateFolderAccess(userId, folderId);

        // s3 키 생성
        String s3Key = String.format("users/%d/sync-folders/%d/%s", userId, folderId, relativePath);

        // s3키로 파일 메타 데이터 조회
        FileMeta fileMeta = fileMetaRepository.findByUserIdAndSyncFolderIdAndS3Key(userId, folderId, s3Key)
                .orElseThrow(() -> new ResourceNotFoundException("파일을 찾을 수 없습니다: " + relativePath));

        // 엔티티를 dto로 변환하여 반환
        return convertToDTO(fileMeta);
    }

    /**
     * FileMeta 엔티티를 FileMetaDTO로 변환하는 메서드
     * @param fileMeta 파일 메타데이터 엔티티
     * @return 파일 메타데이터 DTO
     */
    private FileMetaDTO convertToDTO(FileMeta fileMeta) {
        FileMetaDTO dto = new FileMetaDTO();
        dto.setOriginalName(fileMeta.getOriginalName());
        dto.setSize(fileMeta.getSize());
        dto.setMimeType(fileMeta.getMimeType());

        // 해시값 설정
        dto.setHash(fileMeta.getHash());

        // 상대 경로 설정(s3키에서 추출 또는 별도의 필드 "" 사용)
        dto.setRelativePath(extractRelativePath(fileMeta));

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
                .map(this::convertToDTO)
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
        // S3 키로 검색
        List<FileMeta> files = fileMetaRepository.findByUserIdAndS3KeyContaining(userId, keyword);

        // dto로 변환하여 반환
        return files.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

}
