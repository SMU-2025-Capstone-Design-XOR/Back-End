package com.capstone.xor.dto;

import lombok.Getter;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Getter
public class DiffResult {
    private final String relativePath;
    private final InputStream inputStream;
    private final long size;
    private final String mimeType;

    // text diff용 생성자
    public DiffResult(String relativePath, String diffText) {
        this.relativePath = relativePath;
        this.mimeType = "text/plain"; // application/xml 등으로 변경 가능
        this.inputStream = new ByteArrayInputStream(diffText.getBytes(StandardCharsets.UTF_8));
        this.size = diffText.getBytes(StandardCharsets.UTF_8).length;
    }

    // 바이너리 diff용 생성자
    public DiffResult(String relativePath, byte[] binaryData, String mimeType) {
        this.relativePath = relativePath;
        this.mimeType = mimeType;
        this.inputStream = new ByteArrayInputStream(binaryData);
        this.size = binaryData.length;
    }

    public InputStream toInputStream() { return inputStream; }

}
