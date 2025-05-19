package com.capstone.xor.controller;

import org.springframework.core.io.Resource;
import com.capstone.xor.dto.VersionDTO;
import com.capstone.xor.entity.FileMeta;
import com.capstone.xor.entity.VersionMetadata;
import com.capstone.xor.exception.ResourceNotFoundException;
import com.capstone.xor.repository.FileMetaRepository;
import com.capstone.xor.repository.VersionMetadataRepository;
import com.capstone.xor.service.DiffService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.List;

@RestController
@RequestMapping("/users/{userId}/sync-folders/{folderId}/files/{fileId}")
public class FileVersionController {

    private final VersionMetadataRepository versionMetadataRepository;
    private final DiffService diffService;
    private final FileMetaRepository fileMetaRepository;

    public FileVersionController(
            VersionMetadataRepository versionMetadataRepository,
            DiffService diffService,
            FileMetaRepository fileMetaRepository) {
        this.versionMetadataRepository = versionMetadataRepository;
        this.diffService = diffService;
        this.fileMetaRepository = fileMetaRepository;
    }

    @GetMapping("/versions")
    public ResponseEntity<List<VersionDTO>> getFileVersions(
            @PathVariable Long userId,
            @PathVariable Long folderId,
            @PathVariable ("fileId") Long fileMetaId) {

        List<VersionMetadata> versions = versionMetadataRepository.findByFileMeta_IdOrderByVersionNumberAsc(fileMetaId);

        List<VersionDTO> result = versions.stream()
                .map(v -> VersionDTO.builder()
                        .versionId(v.getId())
                        .versionNumber(v.getVersionNumber())
                        .versionType(v.getVersionType())
                        .s3Key(v.getS3Key())
                        .createdDate(v.getCreatedDate())
                        .build())
                .toList();

        return ResponseEntity.ok(result);
    }

    @GetMapping("/{version}")
    public ResponseEntity<Resource> restoreFileVersion(
            @PathVariable Long userId,
            @PathVariable Long folderId,
            @PathVariable("fileId") Long fileMetaId,
            @PathVariable int version) {

        FileMeta fileMeta = fileMetaRepository.findById(fileMetaId)
                .orElseThrow(() -> new ResourceNotFoundException("File Meta not found"));
        if (!fileMeta.getUser().getId().equals(userId) ||
                !fileMeta.getSyncFolder().getId().equals(folderId)) {
            throw new AccessDeniedException("권한 없음");
        }

        File restoredZip = diffService.restoreFileToVersion(fileMetaId, version);

        Resource resource = new FileSystemResource(restoredZip);
        String fileName = fileMeta.getOriginalName() + "-v" + version + ".zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(restoredZip.length())
                .body(resource);
    }
}
