package com.qroad.be.controller;

import com.qroad.be.dto.ArticleUpdateRequestDTO;
import com.qroad.be.service.PaperService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/articles")
@RequiredArgsConstructor
public class AdminArticleController {

    private final PaperService paperService;

    @PostMapping("/{id}")
    public ResponseEntity<com.qroad.be.dto.ArticleUpdateResponseDTO> updateArticle(@PathVariable Long id,
            @RequestBody ArticleUpdateRequestDTO request) {
        com.qroad.be.dto.ArticleUpdateResponseDTO response = paperService.updateArticle(id, request);
        return ResponseEntity.ok(response);
    }
}
