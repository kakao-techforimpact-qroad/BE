package com.qroad.be.security;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminPrincipal {

    private final Long adminId;
    private final String loginId;


}
