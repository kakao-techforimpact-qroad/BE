package com.qroad.be.domain.article;

import com.qroad.be.domain.article_policy_related.ArticlePolicyRelatedRepository;
import com.qroad.be.domain.article_related.ArticleRelatedRepository;
import com.qroad.be.domain.user.UserMainDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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
