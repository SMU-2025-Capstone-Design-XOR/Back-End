package com.capstone.xor.repository;

import com.capstone.xor.entity.SyncFolder;
import com.capstone.xor.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SyncFolderRepository extends JpaRepository<SyncFolder, Long> {

    List<SyncFolder> findByUser(User user);
    Boolean existsByUserIdAndFolderPath(Long userId, String folderPath);
}
