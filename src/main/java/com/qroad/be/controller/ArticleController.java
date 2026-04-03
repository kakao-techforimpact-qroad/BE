package com.qroad.be.controller;

import com.qroad.be.service.ArticleService;
import com.qroad.be.dto.ArticlesDetailDTO;
import com.qroad.be.security.UserUuidCookieFilter;
import jakarta.servlet.http.HttpServletRequest;
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
    public ResponseEntity<ArticlesDetailDTO> getArticleDetail(
            @PathVariable("article_id") Long articleId,
            HttpServletRequest request) {
        log.info("in ArticleController: getArticleDetail");

        String userIdentifier = null;
        Object attr = request.getAttribute(UserUuidCookieFilter.REQUEST_ATTR_USER_UUID);
        if (attr instanceof String uuid && !uuid.isBlank()) {
            userIdentifier = uuid;
        }

        ArticlesDetailDTO articlesDetailDTO = articleService.getArticleDetail(articleId, userIdentifier);
        return ResponseEntity.ok(articlesDetailDTO);
    }
}
