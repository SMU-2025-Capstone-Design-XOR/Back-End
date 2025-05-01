package com.capstone.xor.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SyncResult {
    private List<FileMetaDTO> serverFiles; // 서버에 저장된 파일 전체 목록
    private List<String> filesToUpload; // 클라이언트가 서버에 업로드해야 할 파일명
    private List<String> filesToDownload; // 클라이언트가 서버에서 다운로드해야 할 파일명
    private List<String> conflictFiles; // 충돌난 파일명

}

