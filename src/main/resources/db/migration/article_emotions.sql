-- 감정 공감 기능 테이블 생성
-- article_emotions 테이블

CREATE TABLE article_emotions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    article_id BIGINT NOT NULL,
    emotion_type VARCHAR(20) NOT NULL,
    user_identifier VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    -- 유니크 제약조건: 한 사용자는 한 기사에 하나의 감정만 표현 가능
    CONSTRAINT uk_article_user_emotion UNIQUE (article_id, user_identifier, emotion_type),
    
    -- 인덱스: 기사별 감정 타입 조회 최적화
    INDEX idx_article_emotion (article_id, emotion_type),
    
    -- 외래키: 기사 삭제 시 관련 감정도 함께 삭제
    CONSTRAINT fk_article_emotions_article 
        FOREIGN KEY (article_id) 
        REFERENCES articles(id) 
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 감정 타입 값 확인용 주석
-- LIKE: 좋아요
-- HEARTWARMING: 훈훈해요
-- SAD: 슬퍼요
-- ANGRY: 화나요
-- WANT_FOLLOW_UP: 후속기사 원해요
