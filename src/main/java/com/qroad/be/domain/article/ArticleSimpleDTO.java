package com.qroad.be.domain.article;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArticleSimpleDTO {

    private Long id;
    private String title;

}
