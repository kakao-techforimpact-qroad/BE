package com.qroad.be.progress;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PublicationStep {
    PDF_UPLOADING(5, "PDF 업로드 중,,,"),
    PDF_READING(10, "PDF 읽는 중,,,"),
    CHUNKING(40, "기사 청킹 중,,,"),
    SUMMARIZING(60, "기사 본문 요약 중,,,"),
    KEYWORD_EXTRACTING(75, "키워드 추출 중,,,"),
    FINDING_RELATED(90, "연관기사, 연관정책 검색 중,,,"),
    SAVING(95, "저장 중,,,"),
    DONE(100, "완료.");

    private final int progress;
    private final String message;
}
