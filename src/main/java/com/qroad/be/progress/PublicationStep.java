package com.qroad.be.progress;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
/**
 * 신문 발행 비동기 작업의 진행 단계 정의.
 * progress는 프론트 polling UI에 그대로 전달되는 기준 값이다.
 */
public enum PublicationStep {
    PDF_UPLOADING(5, "PDF 업로드 준비 중,,,"),
    PDF_READING(10, "PDF 내용 확인 중,,,"),
    CHUNKING_AND_ANALYZING(20, "기사 분리 및 분석 중,,,"),
    ANALYSIS_FINALIZING(70, "기사 분석 결과 정리 중,,,"),
    KEYWORD_MAPPING(85, "키워드 저장 및 매핑 중,,,"),
    FINDING_RELATED(90, "연관 기사/정책 생성 중,,,"),
    SAVING(95, "최종 저장 중,,,"),
    DONE(100, "처리 완료.");

    private final int progress;
    private final String message;
}
