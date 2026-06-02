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
        com.qroad.be.pdf.UpstageDocumentParseService upstage =
                new com.qroad.be.pdf.UpstageDocumentParseService();
        String upstageKey = System.getenv("UPSTAGE_API_KEY");
        if (upstageKey != null && !upstageKey.isEmpty()) {
            upstage.setApiKey(upstageKey);
        }
        PdfExtractorService service = new PdfExtractorService(new OcrService(), upstage);

        String[] fileNames = {"TalkFile_1838", "TalkFile_1839", "TalkFile_1840", "TalkFile_1841"};
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        Path outputDir = Paths.get("/Users/kim-yusin/article/test_" + timestamp);
        Files.createDirectories(outputDir);
        System.out.println("Saving results to directory: " + outputDir);



        for (String name : fileNames) {
            Path pdfPath = Paths.get("/Users/kim-yusin/article/" + name + ".pdf");
            if (!Files.exists(pdfPath)) {
                System.err.println("File not found: " + pdfPath);
                continue;
            }
            System.out.println("\n=== Processing: " + name + " ===");
            byte[] pdfBytes = Files.readAllBytes(pdfPath);
            PdfExtractorService.ExtractionResult result = service.extractWithImages(pdfBytes);

            Path textOut = outputDir.resolve(name + "_extracted.txt");
            Files.writeString(textOut, result.getText());
            System.out.println("Saved text to: " + textOut);

            List<PdfExtractorService.ArticleImageData> images = result.getArticleImages();
            System.out.println("Found " + images.size() + " images.");
            int idx = 1;
            for (PdfExtractorService.ArticleImageData img : images) {
                String sanitizedTitle = img.getTitle().replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_").trim();
                if (sanitizedTitle.length() > 30) sanitizedTitle = sanitizedTitle.substring(0, 30);
                Path imgPath = outputDir.resolve(name + "_image_" + idx + "_" + sanitizedTitle + ".jpg");
                Files.write(imgPath, img.getImageBytes());
                idx++;
            }
        }
        System.out.println("\nDone extracting all PDFs.");
    }
}
