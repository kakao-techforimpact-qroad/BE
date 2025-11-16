package com.qroad.be.domain.policy;

import com.qroad.be.domain.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "policy")
public class PolicyEntity extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "sub_title", length = 100)
    private String subTitle;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "minister_name", length = 100)
    private String ministerName;

    @Column(name = "original_url", nullable = false, length = 255)
    private String originalUrl;

    @Column(name = "registration_date")
    private ZonedDateTime registrationDate;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String status = "ACTIVE";
}

