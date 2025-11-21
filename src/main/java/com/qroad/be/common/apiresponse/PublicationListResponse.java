package com.qroad.be.common.apiresponse;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.qroad.be.dto.PaperSummaryDto;
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
