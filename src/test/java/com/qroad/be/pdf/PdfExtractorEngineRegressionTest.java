package com.qroad.be.pdf;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfExtractorEngineRegressionTest {

    @Test
    void shouldExtractPage9SchoolSectionTitlesFrom1825Pdf() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("1825.pdf"));
        PdfExtractorEngine engine = new PdfExtractorEngine(new OcrService());
        PdfExtractorEngine.ExtractionResult result = engine.extractWithImages(bytes);

        List<String> titles = result.getArticles().stream()
                .map(PdfArticle::getTitle)
                .collect(Collectors.toList());

        boolean hasAnnam = titles.stream()
                .map(this::normalize)
                .anyMatch(t -> t.contains(normalize("안남 씨앗기금")));
        boolean hasAgainMeet = titles.stream()
                .map(this::normalize)
                .anyMatch(t -> t.contains(normalize("또 만나자 청산초 졸업생")));

        assertTrue(hasAnnam, "9면 '안남 씨앗기금...' 제목이 추출되어야 합니다.");
        assertTrue(hasAgainMeet, "9면 '또 만나자 청산초 졸업생...' 제목이 추출되어야 합니다.");
    }

    @Test
    void shouldPreferMainHeadlineOverSubheadingForBeautyLicenseArticle() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("1825.pdf"));
        PdfExtractorEngine engine = new PdfExtractorEngine(new OcrService());
        PdfExtractorEngine.ExtractionResult result = engine.extractWithImages(bytes);

        List<String> page6Titles = result.getArticles().stream()
                .filter(a -> a.getPage() == 6)
                .map(PdfArticle::getTitle)
                .collect(Collectors.toList());

        boolean hasMainTitle = page6Titles.stream()
                .map(this::normalize)
                .anyMatch(t -> t.contains(normalize("국가자격증 취득한 중학생 미용사")));

        boolean hasSubheadingAsTitle = page6Titles.stream()
                .map(this::normalize)
                .anyMatch(t -> t.startsWith(normalize("방학계획 채워")));

        assertTrue(hasMainTitle, "6면 미용 기사의 메인 타이틀이 추출되어야 합니다.");
        assertFalse(hasSubheadingAsTitle, "6면 미용 기사의 부제목이 메인 타이틀로 추출되면 안 됩니다.");
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }
}

