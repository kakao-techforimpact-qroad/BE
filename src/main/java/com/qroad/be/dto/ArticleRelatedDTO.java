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
    private String imagePath;
    private String imageUrl;

    public ArticleRelatedDTO(Long articleId, String title, String content, String link, String imagePath) {
        this.articleId = articleId;
        this.title = title;
        this.content = content;
        this.link = link;
        this.imagePath = imagePath;
    }

}
