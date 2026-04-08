package com.qroad.be;

import com.qroad.be.pdf.OcrService;
import com.qroad.be.pdf.PdfExtractorService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class PdfExtractorTest {

    @Test
    public void testExtraction() throws Exception {
        System.out.println("Starting PDF extraction test...");
        PdfExtractorService service = new PdfExtractorService(new OcrService());

        Path pdfPath = Paths.get("1825.pdf").toAbsolutePath();
        if (!Files.exists(pdfPath)) {
            System.err.println("File not found: " + pdfPath);
            return;
        }

        byte[] pdfBytes = Files.readAllBytes(pdfPath);
        PdfExtractorService.ExtractionResult result = service.extractWithImages(pdfBytes);

        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        Path outputDir = Paths.get("build", "tmp", "pdf-test", "test_" + timestamp).toAbsolutePath();
        Files.createDirectories(outputDir);
        System.out.println("Saving results to directory: " + outputDir);

        Path textOut = outputDir.resolve("1825_extracted.txt");
        Files.writeString(textOut, result.getText());
        System.out.println("Saved text to: " + textOut);

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
