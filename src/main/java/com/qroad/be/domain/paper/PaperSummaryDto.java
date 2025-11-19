package com.qroad.be.domain.paper;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.Arrays;

@Getter
@Builder
public class PaperSummaryDto {

    private Long id;
    private String title;
    private String body;

    @JsonProperty("published_date")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate publishedDate;


    public static PaperSummaryDto from(PaperEntity paper) {
        String bodyPreview = getBodyPreview(paper.getContent(), 2);

        return PaperSummaryDto.builder()
                .id(paper.getId())
                .title(paper.getTitle())
                .body(bodyPreview)
                .publishedDate(paper.getPublishedDate())
                .build();
    }

    private static String getBodyPreview(String content, int maxLines) {
        if (content == null || content.isEmpty()) {
            return "";
        }

        String[] lines = content.split("\n");
        int lineCount = Math.min(maxLines, lines.length);

        return String.join("\n", Arrays.copyOfRange(lines, 0, lineCount));
    }
}