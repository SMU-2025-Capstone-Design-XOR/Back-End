package com.capstone.xor.service;

import com.capstone.xor.dto.DiffResult;
import com.capstone.xor.entity.FileMeta;
import com.capstone.xor.entity.VersionMetadata;
import com.capstone.xor.entity.VersionType;
import com.capstone.xor.repository.VersionMetadataRepository;
import com.capstone.xor.util.FileUtil;
import lombok.RequiredArgsConstructor;
import name.fraser.neil.plaintext.diff_match_patch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class DiffService {

    private final VersionMetadataRepository versionMetadataRepository;
    private final FileUtil fileUtil;

    @Value("${cloud.aws.s3.bucket}")
    private String bucketName;

    /**
     * 파일 버전 복원: 파일 타입(OOXML/텍스트)에 따라 자동 분기
     */
    public File restoreFileToVersion(Long fileMetaId, int targetVersion) {
        // 원하는 버전까지의 versionmetadata조회 1번이 .snapshot
        List<VersionMetadata> versions = versionMetadataRepository
                .findByFileMeta_IdOrderByVersionNumberAsc(fileMetaId)
                .stream()
                .filter(v -> v.getVersionNumber() <= targetVersion)
                .toList();
        if (versions.isEmpty() || versions.get(0).getVersionType() != VersionType.SNAPSHOT) {
            throw new IllegalStateException("스냅샷(.snapshot) 파일이 존재하지 않습니다.");
        }
        // FileMeta에서 원본 파일명 얻기
        FileMeta fileMeta = versions.get(0).getFileMeta();
        String originalName = fileMeta.getOriginalName(); // ex) mydoc.docx

        // OOXML/텍스트 파일 자동 분기
        if (isOOXMLFile(originalName)) {
            return restoreOoxmlFileFromSnapshotAndDiffs(versions, originalName);
        } else if (isTextFile(originalName)) {
            String restorePath = System.getProperty("java.io.tmpdir") + File.separator + originalName;
            return restorePlainTextFileFromSnapshotAndDiffs(versions, restorePath);
        } else if (isBinaryFile(originalName)) {
            throw new UnsupportedOperationException("바이너리 파일 복원은 지원하지 않습니다.");
        } else {
            throw new IllegalArgumentException("지원하지 않는 파일 형식: " + originalName);
        }
    }
    /**
     * OOXML(압축 구조) 파일 복원
     */
    private File restoreOoxmlFileFromSnapshotAndDiffs(List<VersionMetadata> versions, String originalName) {
        try {
            File snapshotFile = fileUtil.downloadFromS3(versions.get(0).getS3Key());
            File restoreDir = fileUtil.unzipToTempDir(snapshotFile);

            for (int i = 1; i < versions.size(); i++) {
                VersionMetadata version = versions.get(i);
                File diffFile = fileUtil.downloadFromS3(version.getS3Key());
                String relativePath = extractRelativePathFromDiffKey(version.getS3Key());
                if (isTextFile(relativePath)) {
                    applyTextPatchToFile(restoreDir, relativePath, diffFile);
                } else {
                    applyBinaryPatchToFile(restoreDir, relativePath, diffFile);
                }
            }

            File restoredZip = zipDirectoryToFile(restoreDir);
            File restoredFile = new File(restoredZip.getParent(), originalName);
            boolean renamed = restoredZip.renameTo(restoredFile);
            if (!renamed) {
                Files.copy(restoredZip.toPath(), restoredFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                restoredZip.delete();
            }
            fileUtil.deleteDirectoryRecursively(restoreDir);
            return restoredFile;
        } catch (IOException e) {
            throw new UncheckedIOException("압축 해제 실패", e);
        }
    }

    /**
     * 텍스트 파일 복원 (patch 순차 적용)
     */
    private File restorePlainTextFileFromSnapshotAndDiffs(List<VersionMetadata> versions, String restorePath) {
        VersionMetadata snapshot = versions.get(0);
        File snapshotFile = fileUtil.downloadFromS3(snapshot.getS3Key());
        String restoredText = readFileToString(snapshotFile);

        for (int i = 1; i < versions.size(); i++) {
            VersionMetadata version = versions.get(i);
            File diffFile = fileUtil.downloadFromS3(version.getS3Key());
            String patchText = readFileToString(diffFile);
            if (patchText == null || patchText.isEmpty()) continue;
            try {
                diff_match_patch dmp = new diff_match_patch();

                List<diff_match_patch.Patch> patchesList = dmp.patch_fromText(patchText);
                LinkedList<diff_match_patch.Patch> patches = new LinkedList<>(patchesList);
                Object[] results = dmp.patch_apply(patches, restoredText);
                restoredText = (String) results[0];
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        File restoredFile = new File(restorePath);
        try {
            Files.writeString(restoredFile.toPath(), restoredText);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return restoredFile;
    }
    /**
     * patch 파일의 S3 Key에서 상대경로 추출
     */
    private String extractRelativePathFromDiffKey(String diffKey) {
        int idx = diffKey.indexOf(".diffs/");
        String sub = diffKey.substring(idx + ".diffs/".length());
        int slashIdx = sub.indexOf("/");
        String rel = sub.substring(slashIdx + 1);
        return rel.replaceAll("\\.diff$", "");
    }

    /**
     * 디렉터리를 zip 파일로 압축
     */
    private File zipDirectoryToFile(File dir) {
        try {
            File zipFile = File.createTempFile("restore-", ".zip");
            try (FileOutputStream fos = new FileOutputStream(zipFile);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {
                zipDirRecursive(dir, dir, zos);
            }
            return zipFile;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void zipDirRecursive(File rootDir, File currentDir, ZipOutputStream zos) throws IOException {
        for (File file : Objects.requireNonNull(currentDir.listFiles())) {
            String entryName = rootDir.toPath().relativize(file.toPath()).toString();
            if (file.isDirectory()) {
                zipDirRecursive(rootDir, file, zos);
            } else {
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(file.toPath(), zos);
                zos.closeEntry();
            }
        }
    }

    /**
     * 텍스트 patch 적용 (디렉터리 내 파일)
     */
    private void applyTextPatchToFile(File restoreDir, String relativePath, File diffFile) {
        try {
            File targetFile = new File(restoreDir, relativePath);
            String oldText = targetFile.exists() ?
                    Files.readString(targetFile.toPath(), StandardCharsets.UTF_8) : "";
            String patchText = Files.readString(diffFile.toPath(), StandardCharsets.UTF_8);

            diff_match_patch dmp = new diff_match_patch();
            LinkedList<diff_match_patch.Patch> patches = new LinkedList<>(dmp.patch_fromText(patchText));
            Object[] results = dmp.patch_apply(patches, oldText);
            String restoredText = (String) results[0];

            targetFile.getParentFile().mkdirs();
            Files.writeString(targetFile.toPath(), restoredText, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 바이너리 patch 적용 (디렉터리 내 파일)
     */
    private void applyBinaryPatchToFile(File restoreDir, String relativePath, File diffFile) {
        try {
            File targetFile = new File(restoreDir, relativePath);
            targetFile.getParentFile().mkdirs();
            Files.copy(diffFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // 파일 확장자 판별 함수들
    private boolean isOOXMLFile(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".docx") || lower.endsWith(".xlsx") || lower.endsWith(".pptx");
    }

    private boolean isTextFile(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".xml") || lower.endsWith(".txt") || lower.endsWith(".json") || lower.endsWith(".csv");
    }

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "tif", "bin", "pdf", "zip",
            "docx", "doc", "xlsx", "xls", "pptx", "ppt", "hwp", "hwpx", "xlsb"
    );

    private boolean isBinaryFile(String filename) {
        String lower = filename.toLowerCase();
        int idx = lower.lastIndexOf('.');
        if (idx != -1 && idx < lower.length() - 1) {
            String ext = lower.substring(idx + 1);
            return BINARY_EXTENSIONS.contains(ext);
        }
        return false;
    }


    public List<DiffResult> diffAllFiles(File prevDir, File newDir) {
        List<DiffResult> results = new ArrayList<>();
        diffAllFilesRecursive(prevDir, newDir, "", results);
        return results;
    }

    private void diffAllFilesRecursive(File prevDir, File newDir, String relativePath, List<DiffResult> results) {
        Set<String> allNames = new HashSet<>();
        if (prevDir != null && prevDir.exists())
            allNames.addAll(Arrays.asList(prevDir.list()));
        if (newDir != null && newDir.exists())
            allNames.addAll(Arrays.asList(newDir.list()));

        for (String name : allNames) {
            File prevFile = prevDir != null ? new File(prevDir, name) : null;
            File newFile = newDir != null ? new File(newDir, name) : null;
            String childRelativePath = relativePath.isEmpty() ? name : relativePath + "/" + name;

            if ((prevFile != null && prevFile.isDirectory()) || (newFile != null && newFile.isDirectory())) {
                // 디랙터리라면 재귀 탐색
                diffAllFilesRecursive(
                        prevFile != null && prevFile.isDirectory() ? prevFile : null,
                        newFile != null && newFile.isDirectory() ? newFile : null,
                        childRelativePath, results
                );
            } else {
                // 파일 비교
                if (isTextFile(name)) {
                    String prevText = readFileToString(prevFile);
                    if (prevText == null) {
                        System.out.println("[DIFF] prevFile 읽기 실패 또는 파일 없음: " + (prevFile != null ? prevFile.getAbsolutePath() : "null"));
                    }
                    String newText = readFileToString(newFile);
                    if (newText == null) {
                        System.out.println("[DIFF] newFile 읽기 실패 또는 파일 없음: " + (newFile != null ? newFile.getAbsolutePath() : "null"));
                    }

                    // 둘 다 null이면(두 파일 모두 없음) 무시
                    if (prevText == null && newText == null) {
                        continue;
                    }
                    if (!Objects.equals(prevText, newText)) {
                        diff_match_patch dmp = new diff_match_patch();
                        // patch_make에 null이 들어가지 않도록 ""(빈 문자열)로 대체
                        String safePrevText = prevText == null ? "" : prevText;
                        String safeNewText = newText == null ? "" : newText;

                        LinkedList<diff_match_patch.Diff> diffs = dmp.diff_main(safePrevText, safeNewText);

                        dmp.diff_cleanupSemantic(diffs);
                        LinkedList<diff_match_patch.Patch> patches = dmp.patch_make(prevText, newText);
                        String patchText = dmp.patch_toText(patches);

                        results.add(new DiffResult(childRelativePath, patchText));
                    }
                }
                // 바이너리 파일 처리
                else if (isBinaryFile(name)) {
                    byte[] prevBytes = readFileToBytes(prevFile);
                    byte[] newBytes = readFileToBytes(newFile);

                    // 둘 다 null이면 (존재하지 않음) 무시, 둘 다 같으면 무시
                    if (!Arrays.equals(prevBytes, newBytes)) {
                        // 변경 시 전체 파일을 diff로 저장
                        String mimeType = getMimeTypeByExtension(name);
                        results.add(new DiffResult(childRelativePath, newBytes != null ? newBytes : new byte[0], mimeType));
                    }
                }
            }
        }
    }

    public String readFileToString(File file) {
        if (file == null) {
            System.out.println("[파일읽기] file == null");
            return null;
        }
        if (!file.exists()) {
            System.out.println("[파일읽기] 파일이 존재하지 않음: " + file.getAbsolutePath());
            return null;
        }
        if (!file.canRead()) {
            System.out.println("[파일읽기] 파일 읽기 권한 없음: " + file.getAbsolutePath());
            return null;
        }
        try {
            String content = Files.readString(file.toPath()); // Java 11 이상
            if (content.isEmpty()) {
                System.out.println("[파일읽기] 파일이 비어 있음: " + file.getAbsolutePath());
            }
            return content;
        } catch (IOException e) {
            System.out.println("[파일읽기] IOException 발생: " + file.getAbsolutePath());
            e.printStackTrace();
            return null;
        }
    }

    private byte[] readFileToBytes(File file) {
        if (file == null || !file.exists()) return null;
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final Map<String, String> EXTENSION_TO_MIME = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("tif", "image/tiff"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("zip", "application/zip"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("doc", "application/msword"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("hwp", "application/x-hwp"),
            Map.entry("hwpx", "application/vnd.hancom.hwpx"),
            Map.entry("xlsb", "application/vnd.ms-excel.sheet.binary.macroenabled.12"),
            Map.entry("bin", "application/octet-stream")
    );

    private String getMimeTypeByExtension(String filename) {
        String lower = filename.toLowerCase();
        int idx = lower.lastIndexOf('.');
        if (idx != -1 && idx < lower.length() - 1) {
            String ext = lower.substring(idx + 1);
            return EXTENSION_TO_MIME.getOrDefault(ext, "application/octet-stream");
        }
        return "application/octet-stream";
    }
}
