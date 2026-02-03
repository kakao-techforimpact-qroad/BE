package com.qroad.be.dto;

import com.qroad.be.domain.EmotionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 감정 통계 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmotionStatsDTO {

    private Long articleId;
    private Map<EmotionType, Long> emotions; // 감정별 개수
    private Long totalCount; // 전체 감정 개수
}
