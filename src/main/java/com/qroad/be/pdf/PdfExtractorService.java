package com.qroad.be.pdf;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.function.BiConsumer;

@Service
public class PdfExtractorService {

    private final PdfExtractorEngine engine;

    @Autowired
    public PdfExtractorService(PdfExtractorEngine engine) {
        this.engine = engine;
    }

    /**
     * PDF 바이트에서 기사 텍스트를 추출합니다.
     * 내부적으로 기사 분리 후 디버그/후속 처리용 텍스트 포맷을 반환합니다.
     */
    public String extractText(byte[] pdfBytes) throws IOException {
        return engine.extractText(pdfBytes);
    }

    /**
     * PDF에서 기사 텍스트와 기사별 이미지 정보를 함께 추출합니다.
     */
    public PdfExtractorEngine.ExtractionResult extractWithImages(byte[] pdfBytes) throws IOException {
        return engine.extractWithImages(pdfBytes);
    }

    /**
     * 페이지 진행률 콜백을 받는 추출 메서드입니다.
     * 콜백 파라미터는 (현재 페이지, 전체 페이지)입니다.
     */
    public PdfExtractorEngine.ExtractionResult extractWithImages(
            byte[] pdfBytes,
            BiConsumer<Integer, Integer> progressCallback
    ) throws IOException {
        return engine.extractWithImages(pdfBytes, progressCallback);
    }
}
