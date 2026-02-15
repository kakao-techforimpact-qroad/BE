package com.qroad.be.progress;

import com.qroad.be.dto.PublicationProgressDto;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class PublicationProgressStore {

    private static final long REMOVE_DELAY_MINUTES = 3L;

    private final ConcurrentMap<String, PublicationProgressDto> progressMap = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r);
        thread.setName("publication-progress-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    public void create(String jobId) {
        moveTo(jobId, PublicationStep.PDF_UPLOADING);
    }

    public Optional<PublicationProgressDto> get(String jobId) {
        return Optional.ofNullable(progressMap.get(jobId));
    }

    public void moveTo(String jobId, PublicationStep step) {
        PublicationJobStatus status = step == PublicationStep.DONE
                ? PublicationJobStatus.DONE
                : PublicationJobStatus.PROCESSING;

        progressMap.compute(jobId, (ignored, existing) -> PublicationProgressDto.builder()
                .status(status)
                .progress(step.getProgress())
                .message(step.getMessage())
                .paperId(existing != null ? existing.getPaperId() : null)
                .timestamp(Instant.now())
                .build());

        if (status == PublicationJobStatus.DONE) {
            scheduleRemoval(jobId);
        }
    }

    public void updateChunkingProgress(String jobId, int processed, int total) {
        if (total <= 0) {
            return;
        }

        int boundedProcessed = Math.max(0, Math.min(processed, total));
        int start = PublicationStep.CHUNKING_AND_ANALYZING.getProgress();
        int end = PublicationStep.ANALYSIS_FINALIZING.getProgress();
        int range = Math.max(1, end - start);
        int progress = start + (boundedProcessed * (range - 1)) / total;
        String message = "기사 분리 및 분석 중,,, (" + boundedProcessed + "/" + total + ")";

        progressMap.computeIfPresent(jobId, (ignored, existing) -> PublicationProgressDto.builder()
                .status(PublicationJobStatus.PROCESSING)
                .progress(progress)
                .message(message)
                .paperId(existing.getPaperId())
                .timestamp(Instant.now())
                .build());
    }

    public void markFailed(String jobId, String errorMessage) {
        progressMap.computeIfPresent(jobId, (ignored, existing) -> PublicationProgressDto.builder()
                .status(PublicationJobStatus.FAILED)
                .progress(existing.getProgress())
                .message(errorMessage)
                .paperId(existing.getPaperId())
                .timestamp(Instant.now())
                .build());

        scheduleRemoval(jobId);
    }

    public void markDone(String jobId, Long paperId) {
        progressMap.computeIfPresent(jobId, (ignored, existing) -> PublicationProgressDto.builder()
                .status(PublicationJobStatus.DONE)
                .progress(PublicationStep.DONE.getProgress())
                .message(PublicationStep.DONE.getMessage())
                .paperId(paperId)
                .timestamp(Instant.now())
                .build());

        scheduleRemoval(jobId);
    }

    private void scheduleRemoval(String jobId) {
        cleanupExecutor.schedule(() -> progressMap.remove(jobId), REMOVE_DELAY_MINUTES, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void shutdown() {
        cleanupExecutor.shutdown();
    }
}
