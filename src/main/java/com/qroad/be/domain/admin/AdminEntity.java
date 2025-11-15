package com.qroad.be.domain.admin;

import com.qroad.be.domain.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "admins")
public class AdminEntity extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "login_id", nullable = false, unique = true, length = 100)
    private String loginId;

    @Column(nullable = false, length = 255)
    private String password;

    @Column(name = "press_companies", length = 100)
    private String pressCompanies;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String status = "ACTIVE";
}

