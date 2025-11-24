package com.qroad.be.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyKeywordRelatedDTO {

    private Long policyId;
    private String title;
    private String content;
    private String link;

}
