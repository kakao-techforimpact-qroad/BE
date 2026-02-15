package com.qroad.be.service;

import com.qroad.be.dto.PaperCreateRequestDTO;
import com.qroad.be.progress.PublicationProgressStore;
import com.qroad.be.progress.PublicationStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PublicationAsyncService {

    private final PaperService paperService;
    private final PublicationProgressStore publicationProgressStore;

    @Async("publicationTaskExecutor")
    public void createPublication(String jobId, PaperCreateRequestDTO request, Long adminId) {
        try {
            paperService.createPaperWithArticles(request, adminId, jobId);
            publicationProgressStore.moveTo(jobId, PublicationStep.DONE);
        } catch (Exception e) {
            log.error("Publication async job failed. jobId={}", jobId, e);
            publicationProgressStore.markFailed(jobId, "Task failed: " + e.getMessage());
        }
    }
}
