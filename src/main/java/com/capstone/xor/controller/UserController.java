package com.capstone.xor.controller;

import com.capstone.xor.dto.LoginRequest;
import com.capstone.xor.dto.SignupRequest;
import com.capstone.xor.security.JwtUtil;
import com.capstone.xor.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class UserController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    @PostMapping("/signup")
    public ResponseEntity<String> signup(@RequestBody SignupRequest signupRequest) {
        userService.signup(signupRequest);
        return ResponseEntity.ok("회원가입 성공");
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        boolean success = userService.login(request);
        if (success){ // 로그인 성공 시 토큰 생성
            String token = jwtUtil.generateToken(request.getUsername());
            return ResponseEntity.ok(Map.of("token", token)); // jwt 토큰 json 형식으로 반환
        }
        else{ // 로그인 실패시 에러 메시지 반환
            return ResponseEntity.badRequest().body(Map.of("error","비밀번호가 일치하지 않습니다."));
        }
    }

    @GetMapping("/protected")
    public ResponseEntity<String> getProtectedResource() {
        return ResponseEntity.ok("이 페이지는 인증된 사용자만 접근 가능합니다.");
    }

}
