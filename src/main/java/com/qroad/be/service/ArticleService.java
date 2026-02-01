package com.qroad.be.service;

import com.qroad.be.dto.ArticleRelatedDTO;
import com.qroad.be.dto.EmotionStatsDTO;
import com.qroad.be.dto.PolicyArticleRelatedDTO;
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
    private final ArticleEmotionService articleEmotionService;

    public ArticlesDetailDTO getArticleDetail(Long articleId) {

        // 1. 기본 article 정보 없으면 예외 발생
        ArticlesDetailDTO dto = articleRepository.findArticleDetailById(articleId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "존재하지 않는 articleId = " + articleId));

        // 2. 관련 기사 리스트 추가
        List<ArticleRelatedDTO> relatedArticles = articleRelatedRepository.findArticlesByArticleId(articleId);

        if (relatedArticles != null && !relatedArticles.isEmpty()) {
            dto.setArticleRelatedDTOS(relatedArticles);
        }

        // 3. 정책 키워드 관련 리스트 추가
        List<PolicyArticleRelatedDTO> policyArticleRelatedDTOS = articlePolicyRelatedRepository
                .findPoliciesByArticleId(articleId);

        if (policyArticleRelatedDTOS != null && !policyArticleRelatedDTOS.isEmpty()) {
            dto.setPolicyArticleRelatedDTOS(policyArticleRelatedDTOS);
        }

        // 4. 감정 통계 추가
        EmotionStatsDTO emotionStats = articleEmotionService.getEmotionStats(articleId);
        dto.setEmotionStats(emotionStats);

        return dto;
    }

}
