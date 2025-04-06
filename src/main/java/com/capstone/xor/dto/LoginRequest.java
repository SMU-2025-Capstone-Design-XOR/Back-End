package com.capstone.xor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor // 기본 생성자(spring 요청 데이터 바인딩)
@AllArgsConstructor // 전체 필드 초기화 생성자(빌더에서 사용)
@Builder // 빌더 패턴
public class LoginRequest {
    private String username;
    private String password;
}
