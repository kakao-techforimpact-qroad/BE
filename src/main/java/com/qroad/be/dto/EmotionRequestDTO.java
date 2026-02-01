package com.qroad.be.dto;

import com.qroad.be.domain.EmotionType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 감정 공감 요청 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmotionRequestDTO {

    @NotNull(message = "감정 타입은 필수입니다")
    private EmotionType emotionType;
}
