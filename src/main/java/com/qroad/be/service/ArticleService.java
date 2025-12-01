package com.qroad.be.service;

import com.qroad.be.dto.ArticleRelatedDTO;
import com.qroad.be.dto.PolicyKeywordRelatedDTO;
import com.qroad.be.repository.ArticlePolicyRelatedRepository;
import com.qroad.be.repository.ArticleRelatedRepository;
import com.qroad.be.dto.ArticlesDetailDTO;
import com.qroad.be.repository.ArticleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleService {

    private final ArticleRepository articleRepository;
    private final ArticleRelatedRepository articleRelatedRepository;
    private final ArticlePolicyRelatedRepository articlePolicyRelatedRepository;

    public ArticlesDetailDTO getArticleDetail(Long articleId) {


        // 1. 기본 article 정보 없으면 예외 발생
        ArticlesDetailDTO dto = articleRepository.findArticleDetailById(articleId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "존재하지 않는 articleId = " + articleId
                ));

        // 2. 관련 기사 리스트 추가
        List<ArticleRelatedDTO> relatedArticles =
                articleRelatedRepository.findArticlesByArticleId(articleId);

        if (relatedArticles != null && !relatedArticles.isEmpty()) {
            dto.getArticleRelatedDTOS().addAll(relatedArticles);
        }

        // 3. 정책 키워드 관련 리스트 추가
        List<PolicyKeywordRelatedDTO> policyKeywords =
                articlePolicyRelatedRepository.findPoliciesByArticleId(articleId);

        if (policyKeywords != null && !policyKeywords.isEmpty()) {
            dto.getPolicyKeywordRelatedDTOS().addAll(policyKeywords);
        }

        return dto;
    }

}
