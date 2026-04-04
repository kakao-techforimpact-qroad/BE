package com.qroad.be.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArticleSimpleDTO {

    private Long id;
    private String title;
    private String imagePath;
    private String imageUrl;

    public ArticleSimpleDTO(Long id, String title, String imagePath) {
        this.id = id;
        this.title = title;
        this.imagePath = imagePath;
    }

}
