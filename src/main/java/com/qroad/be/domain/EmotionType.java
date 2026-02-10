package com.qroad.be.domain;

/**
 * 기사에 대한 감정 공감 타입
 */
public enum EmotionType {
    LIKE("좋아요"),
    HEARTWARMING("훈훈해요"),
    SAD("슬퍼요"),
    ANGRY("화나요"),
    WANT_FOLLOW_UP("후속기사 원해요");

    private final String description;

    EmotionType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
