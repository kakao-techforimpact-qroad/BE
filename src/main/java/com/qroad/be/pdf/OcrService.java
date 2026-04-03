package com.qroad.be.pdf;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.File;

@Service
public class OcrService {

    private final ITesseract tesseract;

    public OcrService() {
        tesseract = new Tesseract();
        
        // OS 환경별 Tesseract Data Path 설정
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) {
            System.setProperty("jna.library.path", "/opt/homebrew/lib:/usr/local/lib");
            // Homebrew default on Apple Silicon
            File m1Path = new File("/opt/homebrew/share/tessdata/");
            File intelPath = new File("/usr/local/share/tessdata/");
            if (m1Path.exists()) {
                tesseract.setDatapath(m1Path.getAbsolutePath());
            } else if (intelPath.exists()) {
                tesseract.setDatapath(intelPath.getAbsolutePath());
            }
        } else {
            // Linux/Ubuntu 기본 경로 (또는 환경변수 등을 통해 전달)
            File linuxPath = new File("/usr/share/tesseract-ocr/5/tessdata");
            if (linuxPath.exists()) {
                tesseract.setDatapath(linuxPath.getAbsolutePath());
            }
        }
        
        // 한글 + 영문
        tesseract.setLanguage("kor+eng");
    }

    /**
     * BufferedImage에서 텍스트를 추출 (OCR)
     */
    public String extractTextFromImage(BufferedImage image) {
        try {
            return tesseract.doOCR(image);
        } catch (TesseractException e) {
            System.err.println("OCR Extraction Failed: " + e.getMessage());
            return "";
        } catch (Exception e) {
            System.err.println("OCR Processing Error: " + e.getMessage());
            return "";
        }
    }
}
