package com.qroad.be.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminCreateRequestDTO {
    private String loginId;
    private String password;     // raw password
    private String pressCompany;
}
