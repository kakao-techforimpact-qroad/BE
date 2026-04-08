package com.qroad.be;

import com.qroad.be.pdf.OcrService;
import com.qroad.be.pdf.PdfExtractorService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

public class PdfReporterMissingDumpTest {
    @Test
    void dumpSplitSuspiciousTitles() throws Exception {
        PdfExtractorService service = new PdfExtractorService(new OcrService());
        byte[] pdfBytes = Files.readAllBytes(Paths.get("1825.pdf").toAbsolutePath());
        PdfExtractorService.ExtractionResult result = service.extractWithImages(pdfBytes);
        String text = result.getText();

        String[] blocks = text.split("={60}\\R");
        int idx = 0;
        for (String block : blocks) {
            if (block == null || block.isBlank()) continue;
            idx++;
            if (block.contains("방학계획 채워") || block.contains("국가자격증 취득한 중학생 미용사")) {
                System.out.println("---- HIT BLOCK " + idx + " ----");
                Arrays.stream(block.split("\\R")).limit(20).forEach(System.out::println);
            }
        }
    }
}
