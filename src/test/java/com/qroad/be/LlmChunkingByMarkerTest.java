package com.qroad.be;

import com.qroad.be.external.llm.LlmService;
import com.qroad.be.pdf.OcrService;
import com.qroad.be.pdf.PdfExtractorService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class LlmChunkingByMarkerTest {

    @SuppressWarnings("unchecked")
    @Test
    void chunkByArticleMarker_usesExtractedBlocks() throws Exception {
        byte[] pdfBytes = Files.readAllBytes(Paths.get("1825.pdf").toAbsolutePath());
        PdfExtractorService extractor = new PdfExtractorService(new OcrService());
        String paperContent = extractor.extractWithImages(pdfBytes).getText();

        LlmService llmService = new LlmService(null);
        Method method = LlmService.class.getDeclaredMethod("chunkByArticleMarker", String.class);
        method.setAccessible(true);

        List<String> blocks = (List<String>) method.invoke(llmService, paperContent);
        System.out.println("MARKER_BLOCKS=" + blocks.size());

        assertTrue(blocks.size() >= 35, "마커 기반 기사 청킹 수가 비정상적으로 적습니다.");
        assertTrue(
                blocks.stream().anyMatch(b -> b.contains("국가자격증 취득한 중학생 미용사")),
                "기대한 기사 제목 블록을 찾지 못했습니다."
        );
    }
}

