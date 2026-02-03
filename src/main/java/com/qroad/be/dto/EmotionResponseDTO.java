package com.qroad.be.dto;

import com.qroad.be.domain.EmotionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 감정 공감 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmotionResponseDTO {

    private Long articleId;
    private EmotionType emotionType;
    private Boolean isActive; // true: 추가됨, false: 취소됨
    private Long totalCount; // 해당 감정의 전체 개수
}
