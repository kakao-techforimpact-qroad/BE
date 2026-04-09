package com.qroad.be.uuid;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "user_access_log")
public class UserAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String uuid;

    private String path;

    @Column(nullable = false)
    private LocalDateTime accessTime;

    private Boolean isNewUser;

    @PrePersist
    public void prePersist() {
        this.accessTime = LocalDateTime.now();
    }

}
