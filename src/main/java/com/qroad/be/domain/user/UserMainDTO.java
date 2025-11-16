package com.qroad.be.domain.user;

import com.qroad.be.domain.article.ArticleSimpleDTO;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserMainDTO {

    private Integer articleCount;
    private LocalDate publishedDate;
    private List<ArticleSimpleDTO> articleSimpleDTOS;

}
