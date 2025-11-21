package com.qroad.be.service;

import com.qroad.be.domain.AdminEntity;
import com.qroad.be.dto.ArticlesDetailDTO;
import com.qroad.be.repository.AdminRepository;
import com.qroad.be.repository.ArticlePolicyRelatedRepository;
import com.qroad.be.repository.ArticleRelatedRepository;
import com.qroad.be.repository.ArticleRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final AdminRepository adminRepository;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AdminEntity createAdmin(String loginId, String rawPassword, String pressCompany) {
        AdminEntity admin = AdminEntity.builder()
                .loginId(loginId)
                .password(encoder.encode(rawPassword))
                .pressCompany(pressCompany)
                .status("ACTIVE")
                .build();

        return adminRepository.save(admin);
    }

    public AdminEntity login(String loginId, String rawPassword) {
        return adminRepository.findByLoginId(loginId)
                .filter(admin -> encoder.matches(rawPassword, admin.getPassword()))
                .orElse(null);
    }

    private Long getAdminIdFromSession(HttpSession session) {
        Long adminId = (Long) session.getAttribute("adminId");
        if (adminId == null) {
            throw new RuntimeException("세션 없음 → 로그인 필요");
        }
        return adminId;
    }

}
