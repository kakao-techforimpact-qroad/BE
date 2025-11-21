package com.qroad.be.service;

import com.qroad.be.repository.ArticlePolicyRelatedRepository;
import com.qroad.be.repository.ArticleRelatedRepository;
import com.qroad.be.dto.ArticlesDetailDTO;
import com.qroad.be.repository.ArticleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleService {

    private final ArticleRepository articleRepository;
    private final ArticleRelatedRepository articleRelatedRepository;
    private final ArticlePolicyRelatedRepository articlePolicyRelatedRepository;

    public ArticlesDetailDTO getArticleDetail(Long articleId) {

        ArticlesDetailDTO articlesDetailDTO = articleRepository.findArticleDetailById(articleId);
        articlesDetailDTO.setArticleRelatedDTOS(articleRelatedRepository.findArticlesByArticleId(articleId));
        articlesDetailDTO.setArticlePolicyRelatedDTOS(articlePolicyRelatedRepository.findPoliciesByArticleId(articleId));

        return articlesDetailDTO;
    }

}
