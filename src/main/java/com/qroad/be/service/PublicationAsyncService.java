package com.qroad.be.service;

import com.qroad.be.dto.FinalizeUploadResponse;
import com.qroad.be.dto.PaperCreateRequestDTO;
import com.qroad.be.dto.PaperCreateResponseDTO;
import com.qroad.be.progress.PublicationProgressStore;
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
    private final S3PresignService s3PresignService;

    @Async("publicationTaskExecutor")
    public void createPublication(String jobId, PaperCreateRequestDTO request, Long adminId) {
        try {
            PaperCreateResponseDTO response = paperService.createPaperWithArticles(request, adminId, jobId);
            Long paperId = response.getPaperId();

            // 자동 finalize: temp → paper/{paperId}.pdf 로 이동 + temp 삭제
            FinalizeUploadResponse finalizeResult = s3PresignService.finalizePdfUpload(request.getTempKey(), paperId);
            // DB file_path 갱신
            paperService.updateFilePath(paperId, finalizeResult.getFinalKey());
            log.info("자동 finalize 완료: paperId={}, finalKey={}", paperId, finalizeResult.getFinalKey());

            publicationProgressStore.markDone(jobId, paperId);
        } catch (Exception e) {
            log.error("Publication async job failed. jobId={}", jobId, e);
            publicationProgressStore.markFailed(jobId, "Task failed: " + e.getMessage());
        }
    }
}
