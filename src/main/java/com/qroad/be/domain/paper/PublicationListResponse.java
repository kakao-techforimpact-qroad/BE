package com.qroad.be.domain.paper;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class PublicationListResponse {
    @JsonProperty("total_count")
    private Long totalCount;

    private List<PaperSummaryDto> papers;
}
