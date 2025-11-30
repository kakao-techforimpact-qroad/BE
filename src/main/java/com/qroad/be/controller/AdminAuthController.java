package com.qroad.be.controller;

import com.qroad.be.domain.AdminEntity;
import com.qroad.be.dto.AdminCreateRequestDTO;
import com.qroad.be.dto.AdminLoginRequestDTO;
import com.qroad.be.dto.ArticlesDetailDTO;
import com.qroad.be.service.AdminService;
import com.qroad.be.service.ArticleService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin")
public class AdminAuthController {

    private final AdminService adminService;

    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestBody AdminLoginRequestDTO req, HttpSession session) {

        AdminEntity admin = adminService.login(req.getLoginId(), req.getPassword());

        if (admin == null) {
            return ResponseEntity.status(401).body("아이디 또는 비밀번호가 잘못되었습니다.");
        }

        session.setAttribute("adminId", admin.getId());
        session.setAttribute("loginId", admin.getLoginId());
        session.setAttribute("pressCompany", admin.getPressCompany());

        return ResponseEntity.ok("로그인 성공");
    }

    @PostMapping("/logout")
    public ResponseEntity<String> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok("로그아웃 완료");
    }

    @PostMapping("/register")
    public ResponseEntity<String> createAdmin(@RequestBody AdminCreateRequestDTO req) {

        String message = adminService.createAdmin(
                req.getLoginId(),
                req.getPassword(),
                req.getPressCompany()
        );

        return ResponseEntity.ok(message);
    }

    // 세션 확인용 API
    @GetMapping("/me")
    public ResponseEntity<String> me(HttpSession session) {
        Long adminId = (Long) session.getAttribute("adminId");

        if (adminId == null) {
            return ResponseEntity.status(401).body("로그인 안됨");
        }

        return ResponseEntity.ok("현재 로그인된 관리자 ID: " + adminId);
    }

}
