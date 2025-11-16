package com.qroad.be.domain.article_related;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArticleRelatedDTO {

    private String title;
    private String content;
    private String link;

}
