package com.qroad.be.domain;

import com.qroad.be.common.BaseTimeEntity;
import com.qroad.be.external.policy.PolicyNewsDTO;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

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

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(name = "sub_title", columnDefinition = "TEXT")
    private String subTitle;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "minister_name", length = 100)
    private String ministerName;

    @Column(name = "original_url", nullable = false, columnDefinition = "TEXT")
    private String link;

    @Column(name = "registration_date")
    private ZonedDateTime registrationDate;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String status = "ACTIVE";

    public static PolicyEntity fromDto(PolicyNewsDTO dto) {
        return PolicyEntity.builder()
                .title(dto.getTitle())
                .subTitle(dto.getSubTitle1())
                .content(dto.getDataContents())   // HTML 제거된 순수 텍스트
                .ministerName(dto.getMinisterName())
                .link(dto.getOriginalUrl())
                .registrationDate(parseApproveDate(dto.getApproveDate()))
                .status("ACTIVE")
                .build();
    }

    private static ZonedDateTime parseApproveDate(String dateString) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss");
            LocalDateTime local = LocalDateTime.parse(dateString, formatter);
            return local.atZone(ZoneId.of("Asia/Seoul"));
        } catch (Exception e) {
            return ZonedDateTime.now(ZoneId.of("Asia/Seoul"));
        }
    }
}

