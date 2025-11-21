package com.qroad.be.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArticleRelatedDTO {

    private Long articleId;
    private String title;
    private String content;
    private String link;

}
