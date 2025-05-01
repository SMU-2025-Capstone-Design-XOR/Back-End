package com.capstone.xor.dto;

import com.capstone.xor.entity.SyncFolder;
import lombok.Getter;

import java.util.Date;


@Getter
public class SyncFolderResponse {

    private final Long id;
    private final String folderPath;
    private final Date createdAt;
    private final Long userId;

    public SyncFolderResponse(SyncFolder syncFolder) {
        this.id = syncFolder.getId();
        this.folderPath = syncFolder.getFolderPath();
        this.createdAt = syncFolder.getCreatedAt();
        this.userId = syncFolder.getUser().getId();
    }

}
