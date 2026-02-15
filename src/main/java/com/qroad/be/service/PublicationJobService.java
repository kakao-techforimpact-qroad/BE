package com.qroad.be.service;

import com.qroad.be.dto.PaperCreateRequestDTO;
import com.qroad.be.dto.PublicationProgressDto;
import com.qroad.be.progress.PublicationProgressStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PublicationJobService {

    private final PublicationProgressStore publicationProgressStore;
    private final PublicationAsyncService publicationAsyncService;

    public String start(PaperCreateRequestDTO request, Long adminId) {
        String jobId = UUID.randomUUID().toString();
        publicationProgressStore.create(jobId);
        publicationAsyncService.createPublication(jobId, request, adminId);
        return jobId;
    }

    public PublicationProgressDto getProgress(String jobId) {
        return publicationProgressStore.get(jobId)
                .orElseThrow(() -> new NoSuchElementException("JOB_NOT_FOUND"));
    }
}
