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

        // 1838 elements 덤프 (디버그용)
        {
            Path pdfPath = Paths.get("/Users/kim-yusin/article/TalkFile_1838.pdf");
            if (Files.exists(pdfPath)) {
                byte[] pdfBytes = Files.readAllBytes(pdfPath);
                List<java.util.Map<String, Object>> elements = upstage.parseToElements(pdfBytes);
                StringBuilder dump = new StringBuilder();
                java.util.regex.Pattern fontPat = java.util.regex.Pattern.compile("font-size:(\\d+)px");
                java.util.regex.Pattern tagPat  = java.util.regex.Pattern.compile("<[^>]+>");
                for (java.util.Map<String, Object> el : elements) {
                    String cat  = (String) el.get("category");
                    int page    = el.get("page") instanceof Number ? ((Number)el.get("page")).intValue() : 0;
                    Object cont = el.get("content");
                    String html = "";
                    if (cont instanceof java.util.Map) html = String.valueOf(((java.util.Map<?,?>)cont).get("html"));
                    java.util.regex.Matcher fm = fontPat.matcher(html);
                    int fs = fm.find() ? Integer.parseInt(fm.group(1)) : -1;
                    String text = tagPat.matcher(html.replaceAll("(?i)<br\\s*/?>", " ")).replaceAll("").trim();
                    if (text.length() > 80) text = text.substring(0, 77) + "...";
                    // 좌표: coordinates 필드에서 yMin 추출
                    Object coordsObj = el.get("coordinates");
                    double yMin = -1;
                    if (coordsObj instanceof java.util.List) {
                        java.util.List<?> pts = (java.util.List<?>) coordsObj;
                        for (Object pt : pts) {
                            if (pt instanceof java.util.Map) {
                                Object yVal = ((java.util.Map<?,?>)pt).get("y");
                                if (yVal instanceof Number) {
                                    double y = ((Number)yVal).doubleValue();
                                    yMin = (yMin < 0) ? y : Math.min(yMin, y);
                                }
                            }
                        }
                    }
                    // xMin 추출
                    double xMin = -1;
                    if (coordsObj instanceof java.util.List) {
                        java.util.List<?> ptsX = (java.util.List<?>) coordsObj;
                        for (Object ptX : ptsX) {
                            if (ptX instanceof java.util.Map) {
                                Object xVal = ((java.util.Map<?,?>)ptX).get("x");
                                if (xVal instanceof Number) {
                                    double xv = ((Number)xVal).doubleValue();
                                    xMin = (xMin < 0) ? xv : Math.min(xMin, xv);
                                }
                            }
                        }
                    }
                    dump.append(String.format("[p%d] %-12s fs=%2d  y=%.3f  x=%.3f  %s%n", page, cat, fs, yMin, xMin, text));
                }
                Files.writeString(outputDir.resolve("1838_elements_debug.txt"), dump.toString());
                System.out.println("Saved element debug to: " + outputDir.resolve("1838_elements_debug.txt"));
            }
        }

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
