package com.qroad.be.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminLoginRequestDTO {

    private String loginId;
    private String password;
}
