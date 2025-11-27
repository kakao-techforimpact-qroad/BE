package com.qroad.be.external.policy;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/policy")
public class PolicyController {

    private final PolicyNewsService policyNewsService;

    @PostMapping("/sync")
    public ResponseEntity<String> sync() throws Exception {
        policyNewsService.fetchAndSavePolicies();
        return ResponseEntity.ok("정책 뉴스 수집 및 엑셀 저장 완료");
    }
}