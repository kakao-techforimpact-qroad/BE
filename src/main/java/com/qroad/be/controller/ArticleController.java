package com.qroad.be.controller;

import com.qroad.be.service.ArticleService;
import com.qroad.be.dto.ArticlesDetailDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/articles")
public class ArticleController {

    private final ArticleService articleService;

    @GetMapping("/{article_id}")
    public ResponseEntity<ArticlesDetailDTO> getArticleDetail(@PathVariable("article_id") Long articleId){
        log.info("in ArticleController: getArticleDetail");

        ArticlesDetailDTO articlesDetailDTO = articleService.getArticleDetail(articleId);
        return ResponseEntity.ok(articlesDetailDTO);
    }
}
