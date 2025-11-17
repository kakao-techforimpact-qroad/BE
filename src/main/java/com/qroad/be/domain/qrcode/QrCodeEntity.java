package com.qroad.be.domain.qrcode;

import com.qroad.be.domain.admin.AdminEntity;
import com.qroad.be.domain.common.BaseTimeEntity;
import com.qroad.be.domain.paper.PaperEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "qr_codes")
public class QrCodeEntity extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "qr_key", nullable = false, unique = true, length = 100)
    private String qrKey;

    @Column(name = "qr_image_url", length = 255)
    private String qrImageUrl;

    @Column(name = "target_url", nullable = false, length = 255)
    private String targetUrl;

    @Column(name = "expires_at")
    private ZonedDateTime expiresAt;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String status = "ACTIVE";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paper_id")
    private PaperEntity paper;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_id")
    private AdminEntity admin;
}

