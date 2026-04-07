package com.qroad.be;

import com.qroad.be.pdf.PdfExtractorService;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.qroad.be.pdf.OcrService;

public class PdfExtractorTest {

    @Test
    public void testExtraction() throws Exception {
        System.out.println("Starting PDF extraction test...");
        // UpstageDocumentParseService는 @Value 주입이 필요하므로 테스트에선 mock으로 대체
        com.qroad.be.pdf.UpstageDocumentParseService upstage =
                new com.qroad.be.pdf.UpstageDocumentParseService();
        PdfExtractorService service = new PdfExtractorService(new OcrService(), upstage);
        Path pdfPath = Paths.get("/Users/kim-yusin/Downloads/1825.pdf");
        if (!Files.exists(pdfPath)) {
            System.err.println("File not found: " + pdfPath);
            return;
        }
        
        byte[] pdfBytes = Files.readAllBytes(pdfPath);
        
        PdfExtractorService.ExtractionResult result = service.extractWithImages(pdfBytes);
        
        // Create output directory with timestamp
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        Path outputDir = Paths.get("/Users/kim-yusin/Downloads/test_" + timestamp);
        Files.createDirectories(outputDir);
        System.out.println("Saving results to directory: " + outputDir);
        
        // Save text
        Path textOut = outputDir.resolve("1825_extracted.txt");
        Files.writeString(textOut, result.getText());
        System.out.println("Saved text to: " + textOut);
        
        // Save images
        List<PdfExtractorService.ArticleImageData> images = result.getArticleImages();
        System.out.println("Found " + images.size() + " images.");
        int idx = 1;
        for (PdfExtractorService.ArticleImageData img : images) {
            String sanitizedTitle = img.getTitle().replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_").trim();
            if (sanitizedTitle.length() > 30) {
                sanitizedTitle = sanitizedTitle.substring(0, 30);
            }
            Path imgPath = outputDir.resolve("1825_image_" + idx + "_" + sanitizedTitle + ".jpg");
            Files.write(imgPath, img.getImageBytes());
            idx++;
        }
        System.out.println("Done extracting PDF.");
    }
}
