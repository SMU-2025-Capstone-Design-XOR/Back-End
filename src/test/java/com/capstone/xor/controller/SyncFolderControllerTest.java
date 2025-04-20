package com.capstone.xor.controller;

import com.capstone.xor.dto.SyncFolderDTO;
import com.capstone.xor.entity.SyncFolder;
import com.capstone.xor.entity.User;
import com.capstone.xor.security.JwtUtil;
import com.capstone.xor.service.SyncFolderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
//import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SyncFolderController.class) // SyncFolderController만 테스트
public class SyncFolderControllerTest {

    @Autowired
    private MockMvc mockMvc; // mockmvc로 컨트롤러 테스트

    @MockitoBean
    private SyncFolderService syncFolderService; // 서비스 계층을 대체

    @MockitoBean
    private JwtUtil jwtUtil; // jwtutil을 대체

    @Autowired
    private ObjectMapper objectMapper; // json 직렬화/역직렬화를 위한 objectmapper

    @TestConfiguration
    static class TestConfig {
        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()); // 모든 요청 허용
            return http.build();
        }
    }

    // 싱크 폴더 추가 테스트
    @Test
    void saveSyncFolder_ShouldReturnCreatedSyncFolder() throws Exception {
        //given: Mock된 서비스가 반환할 데이터 설정
        User mockUser = new User();
        mockUser.setId(1L);

        SyncFolder mockSyncFolder = new SyncFolder();
        mockSyncFolder.setId(1L);
        mockSyncFolder.setFolderPath("/home/user/sync-folder");
        mockSyncFolder.setCreatedAt(new Date());
        mockSyncFolder.setUser(mockUser);

        SyncFolderDTO mockSyncFolderDTO = new SyncFolderDTO(mockSyncFolder);

        Mockito.when(syncFolderService.saveSyncFolder(eq(1L), any(String.class)))
                .thenReturn(mockSyncFolderDTO);

        //when: 클라이언트 요청 데이터 준비
        SyncFolderController.SyncFolderRequest request = new SyncFolderController.SyncFolderRequest();
        request.setFolderPath("/home/user/sync-folder");

        //then: post요청을 보내고 응답 검증
        mockMvc.perform(post("/users/1/sync-folders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                        //.with(SecurityMockMvcRequestPostProcessors.user("testuser").roles("USER"))) // 인증 추가
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.folderPath").value("/home/user/sync-folder"));
    }

    // 특정 유저의 모든 싱크 폴더 조회 테스트
    @Test
    void getFolderByUserId_shouldReturnListOfSyncFolders() throws Exception {
        // 엔티티 생성
        //given: 엔티티 생성
        User user = new User();
        user.setId(1L);

        SyncFolder folderEntity1 = new SyncFolder();
        folderEntity1.setId(1L);
        folderEntity1.setFolderPath("/home/user/sync-folder");
        folderEntity1.setCreatedAt(new Date());
        folderEntity1.setUser(user);

        SyncFolder folderEntity2 = new SyncFolder();
        folderEntity2.setId(2L);
        folderEntity2.setFolderPath("/home/user/documents");
        folderEntity2.setCreatedAt(new Date());
        folderEntity2.setUser(user);

        // 엔티티 -> DTO 변환
        SyncFolderDTO folder1 = new SyncFolderDTO(folderEntity1);
        SyncFolderDTO folder2 = new SyncFolderDTO(folderEntity2);

        List<SyncFolderDTO> mockFolderDTOs = Arrays.asList(folder1, folder2);

        Mockito.when(syncFolderService.getFoldersByUser(1L)).thenReturn(mockFolderDTOs);

        //then: get 요청을 보내고 응답 검증
        mockMvc.perform(get("/users/1/sync-folders")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].folderPath").value("/home/user/sync-folder"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].folderPath").value("/home/user/documents"));
    }

    // 특정 싱크 폴더 삭제 테스트
    @Test
    void deleteSyncFolder_ShouldReturnSuccessMessage() throws Exception {
        //given: Mock된 서비스 동작 설정 (void 메서드이므로 doNothing 사용)
        Mockito.doNothing().when(syncFolderService).deleteSyncFolder(1L, 10L);

        //then: delete요청을 보내고 응답을 검증
        mockMvc.perform(delete("/users/1/sync-folders/10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().string("폴더가 성공적으로 삭제되었습니다."));
    }
}

