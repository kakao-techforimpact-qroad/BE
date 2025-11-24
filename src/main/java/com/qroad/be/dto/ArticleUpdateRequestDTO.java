package com.qroad.be.dto;

import lombok.Data;
import java.util.List;

@Data
public class ArticleUpdateRequestDTO {
    private String summary;
    private List<String> keywords;
}
