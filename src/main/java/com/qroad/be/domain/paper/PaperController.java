package com.qroad.be.domain.paper;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class PaperController {

    private final PaperService paperService;


     //API: 발행된 신문 리스트 조회
    @GetMapping("/publications")
    public ResponseEntity<PublicationListResponse> getPublications(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit) {

        PublicationListResponse response = paperService.getPublications(page, limit);
        return ResponseEntity.ok(response);
    }

    //API: 발행 상세 조회
    @GetMapping("/publications/{paperId}")
    public ResponseEntity<PublicationDetailResponse> getPublicationDetail(
            @PathVariable Long paperId) {

        PublicationDetailResponse response = paperService.getPublicationDetail(paperId);
        return ResponseEntity.ok(response);
    }

    //API: QR 발행
    @PostMapping("/qr/{paperId}")
    public ResponseEntity<QrCodeResponse> generateQrCode(
            @PathVariable Long paperId) {

        QrCodeResponse response = paperService.generateQrCode(paperId);
        return ResponseEntity.ok(response);
    }
}
