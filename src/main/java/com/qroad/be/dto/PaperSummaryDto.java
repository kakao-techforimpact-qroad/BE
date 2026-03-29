package com.qroad.be.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.qroad.be.domain.AdminEntity;
import com.qroad.be.domain.PaperEntity;
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
    private String admin;

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
                .admin(resolveAdminName(paper.getAdmin()))
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

    private static String resolveAdminName(AdminEntity admin) {
        if (admin == null) {
            return "";
        }
        if (admin.getPressCompany() != null && !admin.getPressCompany().isBlank()) {
            return admin.getPressCompany();
        }
        return admin.getLoginId() != null ? admin.getLoginId() : "";
    }
}
