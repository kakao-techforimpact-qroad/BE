package com.qroad.be.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperCreateResponseDTO {
    private Long paperId;
    private Integer articleCount;
    private List<ArticleResponseDTO> articles;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ArticleResponseDTO {
        private Long id;
        private String title;
        private String summary;
        private List<String> keywords;
    }
}
