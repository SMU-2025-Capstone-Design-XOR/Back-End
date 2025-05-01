package com.capstone.xor.service;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.capstone.xor.dto.SyncFolderResponse;
import com.capstone.xor.entity.FileMeta;
import com.capstone.xor.entity.SyncFolder;
import com.capstone.xor.entity.User;
import com.capstone.xor.repository.FileMetaRepository;
import com.capstone.xor.repository.SyncFolderRepository;
import com.capstone.xor.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncFolderServiceTest {

    @Mock
    private SyncFolderRepository syncFolderRepository;

    @Mock
    private FileMetaRepository fileMetaRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AmazonS3 amazonS3;

    @InjectMocks
    private SyncFolderService syncFolderService;

    private final String TEST_BUCKET_NAME = "test-bucket";

    @BeforeEach
    void setUp() {
        // bucketName 필드 설정 (ReflectionTestUtils를 사용하여 private 필드 설정)
        ReflectionTestUtils.setField(syncFolderService, "bucketName", TEST_BUCKET_NAME);
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
        SyncFolderResponse savedSyncFolder = syncFolderService.saveSyncFolder(1L, "/home/user/sync-folder");

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
        List<SyncFolderResponse> folderDTOs = syncFolderService.getFoldersByUser(1L);

        // Then: 결과 검증
        assertNotNull(folderDTOs);
        assertEquals(2, folderDTOs.size());

        Mockito.verify(userRepository).findById(1L);
        Mockito.verify(syncFolderRepository).findByUser(mockUser);
    }

    @Test
    void deleteSyncFolder_ShouldDeleteFolderAndAllFiles() {
        // Given: Mock된 사용자, 폴더, 파일 데이터 설정
        User mockUser = new User();
        mockUser.setId(1L);

        SyncFolder mockSyncFolder = new SyncFolder();
        mockSyncFolder.setId(10L);
        mockSyncFolder.setUser(mockUser);
        mockSyncFolder.setFolderPath("/test/path");

        // 폴더 내 파일 목록 Mock
        FileMeta file1 = new FileMeta();
        file1.setId(101L);
        file1.setS3Key("users/1/10/file1.txt");
        file1.setOriginalName("file1.txt");

        FileMeta file2 = new FileMeta();
        file2.setId(102L);
        file2.setS3Key("users/1/10/file2.txt");
        file2.setOriginalName("file2.txt");

        List<FileMeta> mockFiles = Arrays.asList(file1, file2);

        // Mock 설정
        when(syncFolderRepository.findById(10L)).thenReturn(Optional.of(mockSyncFolder));
        when(fileMetaRepository.findBySyncFolderId(10L)).thenReturn(mockFiles);

        // When: 서비스 계층 호출
        syncFolderService.deleteSyncFolder(1L, 10L);

        // Then: 각 단계가 올바르게 호출되었는지 검증

        // 1. S3에서 각 파일 삭제 검증
        verify(amazonS3).deleteObject(eq(TEST_BUCKET_NAME), eq("users/1/10/file1.txt"));
        verify(amazonS3).deleteObject(eq(TEST_BUCKET_NAME), eq("users/1/10/file2.txt"));

        // 2. DB에서 파일 메타데이터 삭제 검증
        verify(fileMetaRepository).deleteAllBySyncFolderId(10L);

        // 3. 폴더 삭제 검증
        verify(syncFolderRepository).delete(eq(mockSyncFolder));
    }

    @Test
    void deleteSyncFolder_WhenS3DeleteFails_ShouldContinueAndDeleteFromDB() {
        // Given: Mock된 사용자, 폴더, 파일 데이터 설정
        User mockUser = new User();
        mockUser.setId(1L);

        SyncFolder mockSyncFolder = new SyncFolder();
        mockSyncFolder.setId(10L);
        mockSyncFolder.setUser(mockUser);
        mockSyncFolder.setFolderPath("/test/path");

        // 폴더 내 파일 목록 Mock
        FileMeta file1 = new FileMeta();
        file1.setId(101L);
        file1.setS3Key("users/1/10/file1.txt");
        file1.setOriginalName("file1.txt");

        FileMeta file2 = new FileMeta();
        file2.setId(102L);
        file2.setS3Key("users/1/10/file2.txt");
        file2.setOriginalName("file2.txt");

        List<FileMeta> mockFiles = Arrays.asList(file1, file2);

        // Mock 설정
        when(syncFolderRepository.findById(10L)).thenReturn(Optional.of(mockSyncFolder));
        when(fileMetaRepository.findBySyncFolderId(10L)).thenReturn(mockFiles);

        // S3 삭제 실패 시나리오 설정 (첫 번째 파일 삭제 시 예외 발생)
        AmazonS3Exception s3Exception = new AmazonS3Exception("S3 삭제 실패");
        doThrow(s3Exception).when(amazonS3).deleteObject(eq(TEST_BUCKET_NAME), eq("users/1/10/file1.txt"));

        // When: 서비스 계층 호출
        syncFolderService.deleteSyncFolder(1L, 10L);

        // Then: 예외가 발생해도 계속 진행되어 DB 삭제가 수행되는지 검증

        // 1. S3에서 각 파일 삭제 시도 검증
        verify(amazonS3).deleteObject(eq(TEST_BUCKET_NAME), eq("users/1/10/file1.txt")); // 실패
        verify(amazonS3).deleteObject(eq(TEST_BUCKET_NAME), eq("users/1/10/file2.txt")); // 성공

        // 2. DB에서 파일 메타데이터 삭제 검증 (예외에도 불구하고 호출되어야 함)
        verify(fileMetaRepository).deleteAllBySyncFolderId(10L);

        // 3. 폴더 삭제 검증 (예외에도 불구하고 호출되어야 함)
        verify(syncFolderRepository).delete(eq(mockSyncFolder));
    }

    @Test
    void deleteSyncFolder_WithNonExistentFolder_ShouldThrowException() {
        // Given: 존재하지 않는 폴더 ID
        when(syncFolderRepository.findById(999L)).thenReturn(Optional.empty());

        // When & Then: 예외 발생 검증
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> syncFolderService.deleteSyncFolder(1L, 999L)
        );

        assertEquals("존재하지 않는 폴더 입니다.", exception.getMessage());

        // S3 삭제나 DB 삭제가 호출되지 않아야 함
        verify(amazonS3, never()).deleteObject(any(), any());
        verify(fileMetaRepository, never()).deleteAllBySyncFolderId(any());
    }

    @Test
    void deleteSyncFolder_WithWrongUser_ShouldThrowException() {
        // Given: 다른 사용자의 폴더
        User mockUser = new User();
        mockUser.setId(2L); // 다른 사용자 ID

        SyncFolder mockSyncFolder = new SyncFolder();
        mockSyncFolder.setId(10L);
        mockSyncFolder.setUser(mockUser);

        when(syncFolderRepository.findById(10L)).thenReturn(Optional.of(mockSyncFolder));

        // When & Then: 권한 없음 예외 발생 검증
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> syncFolderService.deleteSyncFolder(1L, 10L) // 사용자 ID 1로 요청
        );

        assertEquals("이 폴더는 해당 사용자의 싱크 폴더가 아닙니다.", exception.getMessage());

        // S3 삭제나 DB 삭제가 호출되지 않아야 함
        verify(amazonS3, never()).deleteObject(any(), any());
        verify(fileMetaRepository, never()).deleteAllBySyncFolderId(any());
    }
}
