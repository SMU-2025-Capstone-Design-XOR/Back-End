package com.capstone.xor.service;

import com.capstone.xor.dto.LoginRequest;
import com.capstone.xor.dto.SignupRequest;
import com.capstone.xor.entity.User;
import com.capstone.xor.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this); // Mockito 어노테이션 활성화
    }

    @Test
    public void testSignup() {
        // given
        SignupRequest signupRequest = SignupRequest.builder()
                .username("test1")
                .password("test1234")
                .email("test1@gmail.com")
                .build();

        // 비밀번호 인코딩 Mock
        when(passwordEncoder.encode("test1234")).thenReturn("encodedPassword");

        // UserRepository save Mock
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when: 회원가입 실행
        userService.signup(signupRequest);

        // then: 로그인 성공 확인
        LoginRequest loginRequest = LoginRequest.builder()
                .username("test1")
                .password("test1234")
                .build();

        User mockUser = new User();
        mockUser.setUsername("test1");
        mockUser.setPassword("encodedPassword");

        when(userRepository.findByUsername("test1")).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches("test1234", "encodedPassword")).thenReturn(true);

        assertTrue(userService.login(loginRequest));
    }

    @Test
    public void testLogin() {
        // given
        LoginRequest loginRequest = LoginRequest.builder()
                .username("test2")
                .password("test5678")
                .build();

        User mockUser = new User();
        mockUser.setUsername("test2");
        mockUser.setPassword("encodedPassword");

        when(userRepository.findByUsername("test2")).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches("test5678", "encodedPassword")).thenReturn(true);

        // when & then
        assertTrue(userService.login(loginRequest));
    }
}
