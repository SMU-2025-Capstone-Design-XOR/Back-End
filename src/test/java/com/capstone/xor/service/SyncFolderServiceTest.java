package com.capstone.xor.service;

import com.capstone.xor.dto.SyncFolderDTO;
import com.capstone.xor.entity.SyncFolder;
import com.capstone.xor.entity.User;
import com.capstone.xor.repository.SyncFolderRepository;
import com.capstone.xor.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

public class SyncFolderServiceTest {

    private SyncFolderService syncFolderService;

    private SyncFolderRepository syncFolderRepository;
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        syncFolderRepository = Mockito.mock(SyncFolderRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        syncFolderService = new SyncFolderService(syncFolderRepository, userRepository);
    }

    // 싱크 폴더 저장 테스트
    @Test
    void saveSyncFolder_shouldSaveAndReturnSyncFolder() {
        //given : mock된 사용자와 폴더 데이터 설정
        User mockUser = new User();
        mockUser.setId(1L);
        mockUser.setUsername("testuser");
        mockUser.setEmail("test@test.com");

        SyncFolder mockSyncFolder = new SyncFolder();
        mockSyncFolder.setId(1L);
        mockSyncFolder.setUser(mockUser);
        mockSyncFolder.setFolderPath("/home/user/sync-folder");
        mockSyncFolder.setCreatedAt(new Date());

        Mockito.when(userRepository.findById(1L)).thenReturn(Optional.of(mockUser));
        Mockito.when(syncFolderRepository.save(any(SyncFolder.class))).thenReturn(mockSyncFolder);

        //when: 서비스 계층 호출
        SyncFolderDTO savedSyncFolder = syncFolderService.saveSyncFolder(1L, "/home/user/sync-folder");

        //then: 결과 검증
        assertNotNull(savedSyncFolder);
        assertEquals("/home/user/sync-folder", savedSyncFolder.getFolderPath());
        assertEquals(mockUser.getId(), savedSyncFolder.getUserId());

    }

    // 특정 유저의 모든 싱크 폴더 조회 테스트
    @Test
    void getFoldersByUser_ShouldReturnListOfFolders() {
        // Given: Mock된 사용자와 폴더 데이터 설정
        User mockUser = new User();
        mockUser.setId(1L);
        mockUser.setUsername("testuser");

        SyncFolder folder1 = new SyncFolder();
        folder1.setId(1L);
        folder1.setUser(mockUser);
        folder1.setFolderPath("/home/user/sync-folder");

        SyncFolder folder2 = new SyncFolder();
        folder2.setId(2L);
        folder2.setUser(mockUser);
        folder2.setFolderPath("/home/user/documents");

        List<SyncFolder> mockFolders = Arrays.asList(folder1, folder2);

        Mockito.when(userRepository.findById(1L)).thenReturn(Optional.of(mockUser));
        Mockito.when(syncFolderRepository.findByUser(mockUser)).thenReturn(mockFolders);

        // When: 서비스 계층 호출
        List<SyncFolderDTO> folderDTOs = syncFolderService.getFoldersByUser(1L);

        // Then: 결과 검증
        assertNotNull(folderDTOs);
        assertEquals(2, folderDTOs.size());

        Mockito.verify(userRepository).findById(1L);
        Mockito.verify(syncFolderRepository).findByUser(mockUser);
    }

    // 특정 싱크 폴더 삭제 테스트
    @Test
    void deleteSyncFolder_ShouldDeleteSuccessfully() {
        // Given: Mock된 사용자와 폴더 데이터 설정
        User mockUser = new User();
        mockUser.setId(1L);

        SyncFolder mockSyncFolder = new SyncFolder();
        mockSyncFolder.setId(10L);
        mockSyncFolder.setUser(mockUser);

        Mockito.when(syncFolderRepository.findById(10L)).thenReturn(Optional.of(mockSyncFolder));

        // When: 서비스 계층 호출
        syncFolderService.deleteSyncFolder(1L, 10L);

        // Then: 리포지토리가 delete 메서드를 호출했는지 검증
        Mockito.verify(syncFolderRepository).delete(eq(mockSyncFolder));
    }
}
