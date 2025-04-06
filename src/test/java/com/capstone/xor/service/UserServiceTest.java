package com.capstone.xor.service;

import com.capstone.xor.dto.LoginRequest;
import com.capstone.xor.dto.SignupRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
public class UserServiceTest {

    @Autowired
    private UserService userService;

    @Test
    public void testSignup() {
        //given: 빌더 패턴을 사용하여 객체 생성
        SignupRequest signupRequest = SignupRequest.builder()
                .username("test1")
                .password("test1234")
                .email("test1@gmail.com")
                .build();

        //when: 회원가입 실행
        userService.signup(signupRequest);

        //then: 로그인 성공 확인
        LoginRequest loginRequest = LoginRequest.builder()
                .username("test1")
                .password("test1234")
                .build();
        assertTrue(userService.login(loginRequest));
    }

    @Test
    public void testLogin() {
        //given: 회원가입 요청 데이터 준비
        SignupRequest signupRequest = SignupRequest.builder()
                .username("test2")
                .password("test5678")
                .email("test2@gmail.com")
                .build();
        userService.signup(signupRequest);

        // when & then: 로그인 성공 확인
        LoginRequest loginRequest = LoginRequest.builder()
                .username("test2")
                .password("test5678")
                .build();
        assertTrue(userService.login(loginRequest));
    }
}
