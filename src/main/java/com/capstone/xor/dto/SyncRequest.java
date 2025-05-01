package com.capstone.xor.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SyncRequest {
    private List<FileMetaDTO> clientFiles; // 클라이언트가 서버에 보내는 현재 폴더 내 파일들의 메타데이터 목록
    private List<Long> deletedFileIds; // 클라이언트에서 삭제된 파일의 id 목록
}
