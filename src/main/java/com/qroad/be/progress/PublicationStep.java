package com.qroad.be.progress;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PublicationStep {
    PDF_UPLOADING(5, "Preparing uploaded PDF"),
    PDF_READING(10, "Reading PDF content"),
    CHUNKING(40, "Chunking articles"),
    SUMMARIZING(60, "Summarizing article content"),
    KEYWORD_EXTRACTING(75, "Extracting keywords"),
    FINDING_RELATED(90, "Finding related articles and policies"),
    SAVING(95, "Saving publication results"),
    DONE(100, "Publication creation completed");

    private final int progress;
    private final String message;
}
