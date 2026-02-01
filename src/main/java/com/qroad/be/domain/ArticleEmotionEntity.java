package com.qroad.be.domain;

import com.qroad.be.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 기사 감정 공감 Entity
 * 사용자가 기사에 표현한 감정을 저장
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "article_emotions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_article_user_emotion", columnNames = { "article_id", "user_identifier",
                "emotion_type" })
}, indexes = {
        @Index(name = "idx_article_emotion", columnList = "article_id, emotion_type")
})
public class ArticleEmotionEntity extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    private ArticleEntity article;

    @Enumerated(EnumType.STRING)
    @Column(name = "emotion_type", nullable = false, length = 20)
    private EmotionType emotionType;

    @Column(name = "user_identifier", nullable = false, length = 255)
    private String userIdentifier;
}
