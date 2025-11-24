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
public class ArticleChunkDTO {
    private String title;
    private String content;
    private String reporter;
    private String summary;
    private List<String> keywords;
}
