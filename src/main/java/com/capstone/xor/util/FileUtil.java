package com.capstone.xor.util;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
@RequiredArgsConstructor
public class FileUtil {
    private final AmazonS3 amazonS3;

    @Value("${cloud.aws.s3.bucket}")
    private String bucketName;

    // multipartfile 기반 S3 업로드
    public void uploadToS3(String s3Key, MultipartFile file) throws IOException {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(file.getContentType());
        metadata.setContentLength(file.getSize());
        amazonS3.putObject(bucketName, s3Key, file.getInputStream(), metadata);
    }

    // inputstream 기반 S3 업로드
    public void uploadToS3(String s3Key, InputStream is, long size, String mimeType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(mimeType);
        metadata.setContentLength(size);
        amazonS3.putObject(bucketName, s3Key, is, metadata);
    }

    public File downloadFromS3(String s3Key) {
        try {
            S3Object s3Object = amazonS3.getObject(bucketName, s3Key);
            InputStream inputStream = s3Object.getObjectContent();

            // 임시 파일 생성 (확장자 유지)
            String originalName = new File(s3Key).getName();
            String suffix = "";
            int dotIdx = originalName.lastIndexOf(".");
            if(dotIdx != -1) suffix = originalName.substring(dotIdx);
            File tempFile = File.createTempFile("s3download-", suffix);

            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
            inputStream.close();
            return tempFile;
        } catch (IOException e) {
            throw new RuntimeException("S3 다운로드 실패: " + s3Key, e);
        }
    }

    public File unzipToTempDir(File zipFile) throws IOException {
        File tempDir = null;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            tempDir = Files.createTempDirectory("unzip-").toFile();
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(tempDir, entry.getName());
                // zip slip 방지
                String destDirPath = tempDir.getCanonicalPath();
                String destFilePath = outFile.getCanonicalPath();
                if (!destFilePath.startsWith(destDirPath + File.separator)) {
                    throw new IOException("Zip entry is outside of the target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!outFile.mkdirs() && !outFile.exists()) {
                        throw new IOException("디렉터리 생성 실패: " + outFile.getAbsolutePath());
                    }
                } else {
                    if (!outFile.getParentFile().mkdirs() && !outFile.getParentFile().exists()) {
                        throw new IOException("상위 디렉터리 생성 실패: " + outFile.getParentFile().getAbsolutePath());
                    }
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            if (tempDir != null && tempDir.exists()) {
                deleteDirectoryRecursively(tempDir);
            }
            throw new RuntimeException("압축 해제 실패: " + zipFile.getAbsolutePath(), e);
        }
        return tempDir;
    }

    public void deleteDirectoryRecursively(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectoryRecursively(file);
                }
            }
        }
        if (!dir.delete() && dir.exists()) {
            System.err.println("임시 파일/디렉터리 삭제 실패: " + dir.getAbsolutePath());
        }
    }
}
