package com.qroad.be.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.stereotype.Service;

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

    private static final List<String> BAD_KEYWORDS = List.of(
            "발행", "면", "제 ", "호", "www", "http", "기자", "전화", "팩스");
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

    // ──────────────────────── title detection ────────────────────────

    private boolean isProbableTitle(Line line, double bodyMedian) {
        String t = line.getText();

        if (t.length() < 3 || t.length() > 80)
            return false;

        // PDFBox reports slightly larger font sizes than PyMuPDF; use 1.38x to match
        if (line.getMaxSize() < Math.max(bodyMedian * 1.38, 12.5))
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
        if (xs.size() < 8)
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
