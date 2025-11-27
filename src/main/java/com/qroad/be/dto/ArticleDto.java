package com.qroad.be.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ArticleDto {

    private Long id;
    private String title;
    private String summary;
    private List<String> keywords;
}