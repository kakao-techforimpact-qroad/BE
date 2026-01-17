package com.qroad.be.controller;

import com.qroad.be.domain.AdminEntity;
import com.qroad.be.dto.AdminCreateRequestDTO;
import com.qroad.be.dto.AdminLoginRequestDTO;
import com.qroad.be.dto.ArticlesDetailDTO;
import com.qroad.be.security.AdminPrincipal;
import com.qroad.be.security.JwtProvider;
import com.qroad.be.service.AdminService;
import com.qroad.be.service.ArticleService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin")
public class AdminAuthController {

    private final AdminService adminService;
    private final JwtProvider jwtProvider;

    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestBody AdminLoginRequestDTO req) {
        AdminEntity admin = adminService.login(req.getLoginId(), req.getPassword());

        if (admin == null) {
            return ResponseEntity.status(401).body("아이디 또는 비밀번호가 잘못되었습니다.");
        }

        String token = jwtProvider.createToken(admin.getId(), admin.getLoginId());
        return ResponseEntity.ok(token);
    }

    /*@PostMapping("/logout")
    public ResponseEntity<String> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok("로그아웃 완료");
    }*/

    @PostMapping("/register")
    public ResponseEntity<String> createAdmin(@RequestBody AdminCreateRequestDTO req) {

        String message = adminService.createAdmin(
                req.getLoginId(),
                req.getPassword(),
                req.getPressCompany()
        );

        return ResponseEntity.ok(message);
    }

    // jwt admin id 확인용
    @GetMapping("/test")
    public Long test(
            @AuthenticationPrincipal AdminPrincipal admin
    ) {
        return admin.getAdminId();
    }

}
