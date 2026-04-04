package com.qroad.be.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * PDF 파일에서 기사 텍스트를 추출하는 서비스.
 * 입력으로 PDF 바이트 배열을 받아, 전체 기사 텍스트(제목 + 본문)를 하나의 문자열로 반환합니다.
 *
 * 원본:
 * article-extractor/src/main/java/com/article/extractor/service/ArticleExtractorService.java
 * 변경: qroad 패키지에 맞게 이식, 이미지 crop 기능 제거, extractText(byte[]) 메서드 추가
 */
@Service
public class PdfExtractorService {

    private final OcrService ocrService;

    @Autowired
    public PdfExtractorService(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    private static final List<String> BAD_KEYWORDS = List.of(
            "발행", "면", "제 ", "호", "www", "http", "기자", "전화", "팩스");
    // 광고 판별용 키워드 — 본문이 짧으면서 이 단어가 있으면 광고로 간주
    private static final List<String> AD_KEYWORDS = List.of(
            "문의", "할인", "특가", "예약", "판매", "분양", "임대", "모집", "상담",
            "전화", "TEL", "tel", "FAX", "팩스", "가격", "원(", "만원", "% 할인",
            "구인", "구직", "채용", "클리닉", "산후조리");
    // Page header/section patterns like "2 행정", "종합 5" (short 1-3 char section
    // names)
    // Allows longer section names like "우리동네", "동네방네" (4+ chars) to pass as titles
    private static final java.util.regex.Pattern PAGE_HEADER_PATTERN = java.util.regex.Pattern
            .compile("^\\d+\\s+\\S{1,3}$|^\\S{1,3}\\s+\\d+$");
    // Pattern to strip leading/trailing page numbers from merged section header
    // lines
    private static final java.util.regex.Pattern PAGE_NUM_STRIP = java.util.regex.Pattern
            .compile("^\\d{1,3}\\s+|\\s+\\d{1,3}$");
    private static final Pattern CONTINUATION_PATTERN = Pattern.compile("기사\\s*(\\d+)\\s*면\\s*이어짐");

    // 광고 판별 패턴
    // 전화번호: 02-1234-5678 / 010-1234-5678 / (02)1234-5678 형태
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "\\(?\\d{2,3}\\)?[-.]\\d{3,4}[-.]\\d{4}");
    // URL
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)www\\.|http[s]?://");
    // 광고 전용 키워드 — 뉴스 기사에 거의 등장하지 않는 표현만 선별
    // (문의/신청/입주 등 일반 단어는 제외: 기사 본문에도 자주 등장)
    private static final Pattern AD_EXCLUSIVE_KEYWORD = Pattern.compile(
            "선\\s*착\\s*순|분\\s*양\\s*안\\s*내|입\\s*주\\s*문\\s*의|할\\s*인\\s*이\\s*벤\\s*트|모\\s*집\\s*공\\s*고|대표\\s*번호");

    // ──────────────────────── public entry point ────────────────────────

    /**
     * PDF 바이트 배열을 받아 전체 기사 텍스트를 하나의 문자열로 반환합니다.
     * LLM 청킹 로직에 전달할 content를 생성하는 역할입니다.
     */
    public String extractText(byte[] pdfBytes) throws IOException {
        try (InputStream is = new java.io.ByteArrayInputStream(pdfBytes)) {
            ProcessResult result = processPdf(is);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < result.getArticles().size(); i++) {
                PdfArticle a = result.getArticles().get(i);
                sb.append("=".repeat(60)).append("\n");
                sb.append(String.format("[기사 %d] 페이지 %d | %s%n", i + 1, a.getPage(), a.getId()));
                sb.append("=".repeat(60)).append("\n");
                sb.append("제목: ").append(a.getTitle().trim()).append("\n\n");
                sb.append(a.getText().trim()).append("\n\n\n");
            }
            return sb.toString();
        }
    }

    /**
     * PDF 바이트 배열을 받아 텍스트와 기사별 이미지를 함께 반환합니다.
     * PaperService에서 텍스트 추출과 이미지 추출을 한 번의 PDF 로드로 처리하기 위해 사용합니다.
     */
    public ExtractionResult extractWithImages(byte[] pdfBytes) throws IOException {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
            List<PdfArticle> allArticles = new ArrayList<>();
            // 페이지별 내장 이미지 목록 보관 맵
            Map<Integer, List<ImagePositionExtractor.ImageBoundingBox>> pageImagesMap = new HashMap<>();
            ImagePositionExtractor imageExtractor = new ImagePositionExtractor();

            for (int pi = 0; pi < document.getNumberOfPages(); pi++) {
                PDPage page = document.getPage(pi);
                // 미리 이 페이지에 있는 모든 객체 이미지 추출 좌표 확보
                List<ImagePositionExtractor.ImageBoundingBox> pImgs = imageExtractor.extract(page);
                pageImagesMap.put(pi, new ArrayList<>(pImgs));

                List<PdfArticle> pageArticles = buildArticlesForPage(document, pi);
                int idx = 1;
                for (PdfArticle a : pageArticles) {
                    a.setPage(pi + 1);
                    a.setId(String.format("p%02d_a%03d", pi + 1, idx++));
                    allArticles.add(a);
                }
            }

            // 텍스트 생성 (extractText와 동일한 포맷)
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < allArticles.size(); i++) {
                PdfArticle a = allArticles.get(i);
                sb.append("=".repeat(60)).append("\n");
                sb.append(String.format("[기사 %d] 페이지 %d | %s%n", i + 1, a.getPage(), a.getId()));
                sb.append("=".repeat(60)).append("\n");
                sb.append("제목: ").append(a.getTitle().trim()).append("\n\n");
                sb.append(a.getText().trim()).append("\n\n\n");
            }

            // 기사별 이미지 추출 (bodyBbox 안에 걸쳐있는 원래 사진 객체 찾기)
            // AI 카테고리 이미지 매핑 기능으로 변경됨에 따라 PDF 이미지 추출 로직을 비활성화합니다.
            List<ArticleImageData> images = new ArrayList<>();
            /*
            for (PdfArticle a : allArticles) {
                // (기존 이미지 추출 로직 생략) 
            }
            */

            return new ExtractionResult(sb.toString(), images);
        }
    }



    // ──────────────────────── result classes ────────────────────────

    /**
     * extractWithImages()의 반환값.
     * 텍스트(LLM에 넘길 전문)와 기사별 이미지 데이터를 함께 담습니다.
     */
    public static class ExtractionResult {
        private final String text;
        private final List<ArticleImageData> articleImages;

        public ExtractionResult(String text, List<ArticleImageData> articleImages) {
            this.text = text;
            this.articleImages = articleImages;
        }

        public String getText() { return text; }
        public List<ArticleImageData> getArticleImages() { return articleImages; }
    }

    /**
     * 기사 하나의 이미지 데이터.
     * title: PDF에서 추출한 기사 제목 (LLM 결과와 매핑할 때 사용)
     * imageBytes: crop된 JPEG 이미지 바이트
     */
    public static class ArticleImageData {
        private final String title;
        private final byte[] imageBytes;

        public ArticleImageData(String title, byte[] imageBytes) {
            this.title = title;
            this.imageBytes = imageBytes;
        }

        public String getTitle() { return title; }
        public byte[] getImageBytes() { return imageBytes; }
    }

    // ──────────────────────── ad detection ────────────────────────

    /**
     * 본문 텍스트가 광고일 가능성이 높은지 판단합니다.
     *
     * 강한 신호 (하나라도 있으면 광고):
     *   - 전화번호 패턴 (02-xxx, 010-xxx 등)
     *   - URL (www., http)
     *
     * 약한 신호 (2개 이상이면 광고):
     *   - 광고성 키워드 (문의, 모집, 분양, 할인, TEL 등)
     */
    private boolean isLikelyAd(String body) {
        boolean hasPhone = PHONE_PATTERN.matcher(body).find();
        boolean hasUrl   = URL_PATTERN.matcher(body).find();
        boolean hasExclusiveKw = AD_EXCLUSIVE_KEYWORD.matcher(body).find();

        // 강한 신호 2개 이상: 전화번호+URL, 전화번호+광고전용키워드, URL+광고전용키워드
        int strongSignals = (hasPhone ? 1 : 0) + (hasUrl ? 1 : 0) + (hasExclusiveKw ? 1 : 0);
        return strongSignals >= 2;
    }

    // ──────────────────────── title detection ────────────────────────

    private boolean isProbableTitle(Line line, double bodyMedian) {
        String t = line.getText();

        if (t.length() < 3 || t.length() > 80)
            return false;

        // 비볼드: 원래 검증된 1.38 배율 유지 (소제목 오인식 방지)
        // 볼드:   1.15 배율 허용 (볼드 자체가 강한 제목 신호이므로 완화)
        double ratioThreshold = line.isBold() ? 1.15 : 1.38;
        double absThreshold   = line.isBold() ? 10.5 : 12.5;
        if (line.getMaxSize() < Math.max(bodyMedian * ratioThreshold, absThreshold))
            return false;

        if (t.length() < 20) {
            for (String kw : BAD_KEYWORDS) {
                if (t.contains(kw))
                    return false;
            }
        }

        long digits = t.chars().filter(Character::isDigit).count();
        if ((double) digits / Math.max(t.length(), 1) > 0.35)
            return false;

        // Reject page headers like "2 행정", "종합 5", "14 동네방네"
        if (PAGE_HEADER_PATTERN.matcher(t).matches())
            return false;

        // Reject titles starting with certain markers (but allow "…" as valid Korean
        // title prefix)
        if (t.startsWith("▶") || t.startsWith("▷"))
            return false;

        return true;
    }

    // ──────────────────────── column inference ────────────────────────

    private List<Double> inferColumnSplits(List<Double> xs, double pageWidth) {
        if (xs.size() < 4)
            return Collections.emptyList();

        List<Double> sorted = new ArrayList<>(xs);
        Collections.sort(sorted);

        // Compute gaps between consecutive sorted x0 values
        List<double[]> gaps = new ArrayList<>(); // {gapSize, index}
        for (int i = 0; i < sorted.size() - 1; i++) {
            gaps.add(new double[] { sorted.get(i + 1) - sorted.get(i), i });
        }
        gaps.sort((a, b) -> Double.compare(b[0], a[0])); // descending by gap size

        double threshold = pageWidth * 0.10;
        List<Double> splits = new ArrayList<>();

        for (int g = 0; g < Math.min(6, gaps.size()); g++) {
            double gap = gaps.get(g)[0];
            int idx = (int) gaps.get(g)[1];

            if (gap < threshold)
                break;

            double s = (sorted.get(idx) + sorted.get(idx + 1)) / 2.0;

            // Ensure enough data on both sides
            long left = xs.stream().filter(x -> x < s).count();
            long right = xs.size() - left;
            if (left < 4 || right < 4)
                continue;

            // Avoid near-duplicate splits
            boolean tooClose = splits.stream().anyMatch(e -> Math.abs(s - e) < pageWidth * 0.05);
            if (tooClose)
                continue;

            splits.add(s);

            if (splits.size() >= 2)
                break;
        }

        Collections.sort(splits);
        return splits;
    }

    private int assignCol(double x0, List<Double> splits) {
        if (splits.isEmpty())
            return 0;
        for (int i = 0; i < splits.size(); i++) {
            if (x0 < splits.get(i))
                return i;
        }
        return splits.size();
    }

    // ──────────────────────── bbox helpers ────────────────────────

    private double[] clampBbox(double[] b, PDRectangle rect) {
        double x0 = Math.max(0, b[0]);
        double y0 = Math.max(0, b[1]);
        double x1 = Math.min(rect.getWidth(), b[2]);
        double y1 = Math.min(rect.getHeight(), b[3]);
        return new double[] { x0, y0, x1, y1 };
    }

    private List<Line> linesInBbox(List<Line> lines, double[] bbox) {
        double bx0 = bbox[0], by0 = bbox[1], bx1 = bbox[2], by1 = bbox[3];
        List<Line> picked = new ArrayList<>();
        for (Line l : lines) {
            double cx = (l.getBbox()[0] + l.getBbox()[2]) / 2.0;
            double cy = (l.getBbox()[1] + l.getBbox()[3]) / 2.0;
            if (cx >= bx0 && cx <= bx1 && cy >= by0 && cy <= by1) {
                picked.add(l);
            }
        }
        return picked;
    }

    private List<Line> sortLinesReadingOrder(List<Line> lines, double pageWidth) {
        if (lines.size() <= 2) {
            List<Line> copy = new ArrayList<>(lines);
            copy.sort(Comparator.comparingDouble(Line::getY0).thenComparingDouble(Line::getX0));
            return copy;
        }

        List<Double> xs = lines.stream().map(Line::getX0).collect(Collectors.toList());
        List<Double> splits = inferColumnSplits(xs, pageWidth);

        Map<Integer, List<Line>> cols = new TreeMap<>();
        for (Line l : lines) {
            int c = assignCol(l.getX0(), splits);
            cols.computeIfAbsent(c, k -> new ArrayList<>()).add(l);
        }

        for (List<Line> colLines : cols.values()) {
            colLines.sort(Comparator.comparingDouble(Line::getY0).thenComparingDouble(Line::getX0));
        }

        List<Line> ordered = new ArrayList<>();
        for (List<Line> colLines : cols.values()) {
            ordered.addAll(colLines);
        }
        return ordered;
    }

    // ──────────────────────── continuation detection ────────────────────────

    private Integer detectContinuation(String text) {
        Matcher m = CONTINUATION_PATTERN.matcher(text);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return null;
    }

    // ──────────────────────── per-page article extraction ────────────────────────

    private List<PdfArticle> buildArticlesForPage(PDDocument document, int pageIndex) throws IOException {
        PDPage page = document.getPage(pageIndex);
        PDRectangle mediaBox = page.getMediaBox();
        double W = mediaBox.getWidth();
        double H = mediaBox.getHeight();

        PdfTextExtractor.ExtractResult result = PdfTextExtractor.extractLines(page, document, pageIndex);
        List<Line> lines = result.getLines();
        List<Double> sizes = result.getSizes();

        if (lines.isEmpty() || sizes.isEmpty()) {
            return Collections.emptyList();
        }

        // Compute body median font size
        List<Double> sortedSizes = new ArrayList<>(sizes);
        Collections.sort(sortedSizes);
        double bodyMedian = sortedSizes.get(sortedSizes.size() / 2);

        // Detect titles
        List<Line> titles = lines.stream()
                .filter(l -> isProbableTitle(l, bodyMedian))
                .collect(Collectors.toList());

        if (titles.isEmpty()) {
            return Collections.emptyList();
        }

        // Infer page-level column splits
        List<Double> xsTitle = titles.stream().map(Line::getX0).collect(Collectors.toList());
        List<Double> xsAll = lines.stream().map(Line::getX0).collect(Collectors.toList());
        List<Double> xsForSplits = xsTitle.size() >= 8 ? xsTitle : xsAll;
        List<Double> splits = inferColumnSplits(xsForSplits, W);

        // Group titles by column
        Map<Integer, List<Line>> titlesByCol = new TreeMap<>();
        for (Line t : titles) {
            int c = assignCol(t.getX0(), splits);
            titlesByCol.computeIfAbsent(c, k -> new ArrayList<>()).add(t);
        }
        for (List<Line> colTitles : titlesByCol.values()) {
            colTitles.sort(Comparator.comparingDouble(Line::getY0).thenComparingDouble(Line::getX0));
        }

        double margin = W * 0.05;
        List<PdfArticle> articles = new ArrayList<>();

        for (Map.Entry<Integer, List<Line>> entry : titlesByCol.entrySet()) {
            int c = entry.getKey();
            List<Line> tlist = entry.getValue();

            // Column x-range
            double colX0 = (c == 0) ? 0 : splits.get(c - 1);
            double colX1 = (c == splits.size()) ? W : splits.get(c);
            colX0 = Math.max(0, colX0 - margin);
            colX1 = Math.min(W, colX1 + margin);

            for (int i = 0; i < tlist.size(); i++) {
                Line t = tlist.get(i);

                // y-range: from title bottom to next title top or page end
                double y0 = t.getBbox()[3]; // title bottom
                double y1 = (i + 1 < tlist.size()) ? tlist.get(i + 1).getBbox()[1] - 2 : H;

                if (y1 <= y0 + 22)
                    continue;

                // If title width > 40% of page, extend article x-range
                double titleWidth = t.getBbox()[2] - t.getBbox()[0];
                double artX0, artX1;
                if (titleWidth > W * 0.4) {
                    artX0 = Math.max(0, t.getBbox()[0] - margin);
                    artX1 = Math.min(W, t.getBbox()[2] + margin);
                } else {
                    artX0 = colX0;
                    artX1 = colX1;
                }

                double[] bodyBbox = clampBbox(new double[] { artX0, y0, artX1, y1 }, mediaBox);

                List<Line> bodyLines = linesInBbox(lines, bodyBbox);
                List<Line> ordered = sortLinesReadingOrder(bodyLines, W);

                String text = ordered.stream()
                        .map(Line::getText)
                        .collect(Collectors.joining("\n")).trim();

                // 본문 텍스트가 너무 짧으면 광고/이미지 영역으로 판단하고 건너뜀
                if (text.length() < 50) continue;

                // 전화번호·URL·광고성 키워드 기반 광고 필터
                if (isLikelyAd(text)) continue;

                Integer contTo = detectContinuation(text);

                // Clean up title: strip merged page numbers from section headers
                String titleText = PAGE_NUM_STRIP.matcher(t.getText()).replaceAll("").trim();

                PdfArticle article = new PdfArticle();
                article.setTitle(titleText);
                article.setTitleBbox(t.getBbox());
                article.setBodyBbox(bodyBbox);
                article.setText(text);
                article.setContinuationToPage(contTo);
                article.setColumnIndex(c);

                articles.add(article);
            }
        }

        // Sort by title y0 then x0 (reading order)
        articles.sort(Comparator.comparingDouble((PdfArticle a) -> a.getTitleBbox()[1])
                .thenComparingDouble(a -> a.getTitleBbox()[0]));

        return articles;
    }

    // ──────────────────────── internal process ────────────────────────

    private ProcessResult processPdf(InputStream pdfStream) throws IOException {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfStream.readAllBytes())) {
            List<PdfArticle> allArticles = new ArrayList<>();

            for (int pi = 0; pi < document.getNumberOfPages(); pi++) {
                List<PdfArticle> pageArticles = buildArticlesForPage(document, pi);
                int idx = 1;
                for (PdfArticle a : pageArticles) {
                    a.setPage(pi + 1);
                    a.setId(String.format("p%02d_a%03d", pi + 1, idx++));
                    allArticles.add(a);
                }
            }

            return new ProcessResult(allArticles);
        }
    }

    // ──────────────────────── result holder ────────────────────────

    public static class ProcessResult {
        private final List<PdfArticle> articles;

        public ProcessResult(List<PdfArticle> articles) {
            this.articles = articles;
        }

        public List<PdfArticle> getArticles() {
            return articles;
        }
    }
}
