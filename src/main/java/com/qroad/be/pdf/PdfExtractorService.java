package com.qroad.be.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.BiConsumer;
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
@Slf4j
@Service
public class PdfExtractorService {

    private final OcrService ocrService;
    private final UpstageDocumentParseService upstageService;

    @Autowired
    public PdfExtractorService(OcrService ocrService, UpstageDocumentParseService upstageService) {
        this.ocrService = ocrService;
        this.upstageService = upstageService;
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
    private static final Pattern CONTINUED_FROM_PATTERN = Pattern.compile("^(\\d+)면(에서)?\\s*이어[짐서]");

    // OCR 텍스트 내 전화번호 형태 매칭 (예: 000-0000-0000, 043-000-0000, 043)000-0000)
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\d{2,3}\\s*[-)]\\s*\\d{3,4}\\s*-\\s*\\d{4}");

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
        return extractWithImages(pdfBytes, null);
    }

    public ExtractionResult extractWithImages(byte[] pdfBytes, BiConsumer<Integer, Integer> progressCallback) throws IOException {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
            int totalPages = document.getNumberOfPages();
            Map<Integer, List<ImagePositionExtractor.ImageBoundingBox>> pageImagesMap = new HashMap<>();
            ImagePositionExtractor imageExtractor = new ImagePositionExtractor();

            // 1. PDFBox로 전체 처리 + 문제 페이지 여부 동시 확인
            List<PdfArticle> pdfboxArticles = new ArrayList<>();
            boolean hasProblematicPage = false;

            for (int pi = 0; pi < totalPages; pi++) {
                PDPage page = document.getPage(pi);
                List<ImagePositionExtractor.ImageBoundingBox> pImgs = imageExtractor.extract(page);
                pageImagesMap.put(pi, new ArrayList<>(pImgs));

                if (!hasProblematicPage && isProblematicPage(document, pi)) {
                    log.info("[Hybrid] 페이지 {} — 문제 페이지 감지", pi + 1);
                    hasProblematicPage = true;
                }

                List<PdfArticle> pageArticles = buildArticlesForPage(document, pi);
                int idx = 1;
                for (PdfArticle a : pageArticles) {
                    a.setPage(pi + 1);
                    a.setId(String.format("p%02d_a%03d", pi + 1, idx++));
                    pdfboxArticles.add(a);
                }
                if (progressCallback != null) {
                    progressCallback.accept(pi + 1, totalPages);
                }
            }

            // 2. 문제 페이지 있으면 Upstage 1회 호출로 전체 교체
            List<PdfArticle> allArticles = pdfboxArticles;
            if (hasProblematicPage) {
                log.info("[Hybrid] Upstage 전체 PDF 1회 처리 시작");
                List<PdfArticle> upstageArticles = extractAllWithUpstage(pdfBytes);
                if (!upstageArticles.isEmpty()) {
                    log.info("[Hybrid] Upstage 결과 {}개 → 전체 교체", upstageArticles.size());
                    for (int i = 0; i < upstageArticles.size(); i++) {
                        PdfArticle a = upstageArticles.get(i);
                        if (a.getPage() == 0) a.setPage(1);
                        a.setId(String.format("up%03d", i + 1));
                    }
                    allArticles = upstageArticles;
                } else {
                    log.warn("[Hybrid] Upstage 결과 없음 → PDFBox 결과 유지");
                }
            }

            mergeArticleContinuations(allArticles);
            mergeShortFragments(allArticles);

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

    // ──────────────────────── title detection ────────────────────────

    private boolean isProbableTitle(Line line, double bodyMedian, List<Double> splits, double pageWidth) {
        String t = line.getText();

        if (t.length() < 3 || t.length() > 80)
            return false;

        // 1.5x 단일 기준 (볼드 여부 무관) — 소제목 오탐 감소
        // 단, 컬럼 너비 초과(Wide-span) 블록은 낮은 임계값 허용 (명백한 대형 헤드라인)
        double lineWidth = line.getBbox()[2] - line.getBbox()[0];
        double colWidth = splits.isEmpty() ? pageWidth : pageWidth / (splits.size() + 1);
        boolean isWideSpan = lineWidth > colWidth * 1.5;

        // 볼드: 1.20x / 비볼드: 1.45x / wide-span(컬럼 폭 초과): 1.20x
        double ratioThreshold = (line.isBold() || isWideSpan) ? 1.20 : 1.45;
        double absThreshold   = (line.isBold() || isWideSpan) ? 11.0 : 13.0;
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

        double threshold = pageWidth * 0.08;
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

            if (splits.size() >= 3)
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

    private double centerX(Line line) {
        double[] b = line.getBbox();
        return (b[0] + b[2]) / 2.0;
    }

    private List<Line> mergeWrappedTitleLines(List<Line> sortedTitles, double bodyMedian, double pageWidth) {
        if (sortedTitles == null || sortedTitles.size() <= 1) {
            return sortedTitles == null ? Collections.emptyList() : new ArrayList<>(sortedTitles);
        }

        List<Line> merged = new ArrayList<>();
        boolean[] consumed = new boolean[sortedTitles.size()];

        for (int i = 0; i < sortedTitles.size(); i++) {
            if (consumed[i]) {
                continue;
            }
            Line current = sortedTitles.get(i);
            consumed[i] = true;

            while (true) {
                int bestIdx = -1;
                double bestGap = Double.MAX_VALUE;

                for (int j = i + 1; j < sortedTitles.size(); j++) {
                    if (consumed[j]) {
                        continue;
                    }
                    Line cand = sortedTitles.get(j);
                    double gap = cand.getY0() - current.getY1();

                    // y 정렬 상태에서 너무 멀면 중단
                    if (gap > Math.max(36.0, bodyMedian * 2.4)) {
                        break;
                    }
                    if (!shouldMergeTitleLines(current, cand, bodyMedian, pageWidth)) {
                        continue;
                    }
                    if (gap < bestGap) {
                        bestGap = gap;
                        bestIdx = j;
                    }
                }

                if (bestIdx < 0) {
                    break;
                }

                current = mergeTitleLines(current, sortedTitles.get(bestIdx));
                consumed[bestIdx] = true;
            }

            merged.add(current);
        }

        merged.sort(Comparator.comparingDouble(Line::getY0).thenComparingDouble(Line::getX0));
        return merged;
    }

    private boolean shouldMergeTitleLines(Line upper, Line lower, double bodyMedian, double pageWidth) {
        double gap = lower.getY0() - upper.getY1();
        if (gap < -14) {
            return false;
        }
        if (gap > Math.max(18.0, bodyMedian * 1.3)) {
            return false;
        }

        // 본문급 폰트(부제목 등) 오병합 방지
        if (Math.min(upper.getMaxSize(), lower.getMaxSize()) < Math.max(bodyMedian * 1.35, 13.5)) {
            return false;
        }

        double cxDiff = Math.abs(centerX(upper) - centerX(lower));
        if (cxDiff > pageWidth * 0.18) {
            return false;
        }

        double[] ub = upper.getBbox();
        double[] lb = lower.getBbox();
        double overlap = Math.max(0, Math.min(ub[2], lb[2]) - Math.max(ub[0], lb[0]));
        double minWidth = Math.min(upper.getWidth(), lower.getWidth());
        if (minWidth <= 0) {
            return false;
        }

        // 두 번째 줄이 짧은 인용구 제목일 수 있어 기본 임계값을 완화
        if (overlap / minWidth < 0.30) {
            return false;
        }

        double maxFont = Math.max(upper.getMaxSize(), lower.getMaxSize());
        double minFont = Math.min(upper.getMaxSize(), lower.getMaxSize());
        return maxFont > 0 && (maxFont - minFont) / maxFont <= 0.30;
    }

    private Line mergeTitleLines(Line upper, Line lower) {
        String text = (upper.getText() + " " + lower.getText()).replaceAll("\\s+", " ").trim();
        double[] ub = upper.getBbox();
        double[] lb = lower.getBbox();
        double[] bbox = new double[] {
                Math.min(ub[0], lb[0]),
                Math.min(ub[1], lb[1]),
                Math.max(ub[2], lb[2]),
                Math.max(ub[3], lb[3])
        };
        return new Line(text, bbox, Math.max(upper.getMaxSize(), lower.getMaxSize()), upper.isBold() || lower.isBold());
    }

    private double nextTitleTopByAlignedX(List<Line> tlist, int currentIndex, double pageWidth, double defaultY) {
        double curCx = centerX(tlist.get(currentIndex));
        for (int j = currentIndex + 1; j < tlist.size(); j++) {
            Line n = tlist.get(j);
            if (Math.abs(centerX(n) - curCx) <= pageWidth * 0.22) {
                return n.getBbox()[1] - 2;
            }
        }
        return defaultY;
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
            int c = assignCol(centerX(l), splits);
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

        // 1차: 모든 라인 x0으로 splits 초안 계산 (title 감지용)
        List<Double> splitsAll = inferColumnSplits(
                lines.stream().map(Line::getX0).collect(Collectors.toList()), W);

        // Detect titles (wide-span 판별에 splitsAll 사용)
        Set<Line> titleSet = new HashSet<>();
        for (Line l : lines) {
            if (isProbableTitle(l, bodyMedian, splitsAll, W)) {
                titleSet.add(l);
            }
        }
        if (titleSet.isEmpty()) return Collections.emptyList();

        // 2차: title x0으로 splits 재계산 (OLD 방식 — 더 정확한 컬럼 경계)
        List<Double> xsTitle = titleSet.stream().map(Line::getX0).collect(Collectors.toList());
        List<Double> xsAll   = lines.stream().map(Line::getX0).collect(Collectors.toList());
        List<Double> splits  = inferColumnSplits(xsTitle.size() >= 8 ? xsTitle : xsAll, W);

        // ── Y-based body assignment ────────────────────────────────────
        // 각 본문 라인을 '바로 위에 있는 제목'에 할당합니다.
        // 1. 같은 컬럼 내에서 y0 < 본문 cy 인 가장 가까운 제목 우선
        // 2. 같은 컬럼 제목이 없으면 y0 < 본문 cy 인 가장 가까운 제목(어떤 컬럼이든)
        // 효과: 와이드스팬 기사(전면 폭 기사)도 정확히 그 제목에 귀속됨

        // 제목들을 y0 순으로 정렬
        List<Line> slotTitles = new ArrayList<>(titleSet);
        slotTitles.sort(Comparator.comparingDouble(Line::getY0));

        Map<Line, Integer> titleToSlotIdx = new IdentityHashMap<>();
        List<List<Line>> slotBodies = new ArrayList<>();
        for (int i = 0; i < slotTitles.size(); i++) {
            titleToSlotIdx.put(slotTitles.get(i), i);
            slotBodies.add(new ArrayList<>());
        }

        for (Line line : lines) {
            if (titleSet.contains(line)) continue; // 제목 라인은 슬롯 리스트에 이미 있음

            double cy = (line.getBbox()[1] + line.getBbox()[3]) / 2.0;
            int lineCol = assignCol(line.getBbox()[0], splits);

            // 1순위: 같은 컬럼에서 cy 바로 위 제목 (y0 < cy 중 최대)
            Line best = null;
            double bestY = -1;
            for (Line t : slotTitles) {
                if (t.getY0() >= cy) break; // y0 오름차순 정렬이므로 이후는 볼 필요 없음
                if (assignCol(t.getBbox()[0], splits) == lineCol) {
                    best = t;
                    bestY = t.getY0();
                }
            }

            // 2순위: 컬럼 무관 — cy 바로 위 제목 (y0 < cy 중 최대)
            if (best == null) {
                for (Line t : slotTitles) {
                    if (t.getY0() >= cy) break;
                    best = t; // y0 순 정렬이므로 마지막으로 갱신되는 것이 가장 가까운 위쪽 제목
                }
            }

            if (best != null) {
                slotBodies.get(titleToSlotIdx.get(best)).add(line);
            }
        }

        // ── Suffix Anchoring ─────────────────────────────────────────────
        // Detect reporter name / email at the end of each article body.
        // Lines matching the reporter pattern after the last substantive content
        // are stripped from the body (reporter info is in the LLM prompt anyway).
        // This prevents trailing reporter lines from bleeding into the next article
        // when the reading-order walk assigns them to the wrong slot.
        java.util.regex.Pattern reporterSuffix = java.util.regex.Pattern.compile(
                "([가-힣]{2,4})\\s*(기자|기지)\\b|[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

        List<PdfArticle> articles = new ArrayList<>();

        for (int si = 0; si < slotTitles.size(); si++) {
            Line t = slotTitles.get(si);
            List<Line> bodyLines = slotBodies.get(si);

            // Remove trailing reporter/email lines (suffix anchoring)
            int lastContentIdx = bodyLines.size() - 1;
            while (lastContentIdx >= 0 &&
                    reporterSuffix.matcher(bodyLines.get(lastContentIdx).getText()).find()) {
                lastContentIdx--;
            }
            List<Line> contentLines = (lastContentIdx >= 0)
                    ? bodyLines.subList(0, lastContentIdx + 1)
                    : bodyLines;

            List<Line> ordered = sortLinesReadingOrder(contentLines, W);
            String text = ordered.stream()
                    .map(Line::getText)
                    .collect(Collectors.joining("\n")).trim();

            if (text.length() < 120) continue;
            if (isLikelyAd(text)) continue;

            // Body bbox: bounding box of all assigned lines
            double bx0 = contentLines.stream().mapToDouble(l -> l.getBbox()[0]).min().orElse(0);
            double by0 = t.getBbox()[3];
            double bx1 = contentLines.stream().mapToDouble(l -> l.getBbox()[2]).max().orElse(W);
            double by1 = contentLines.stream().mapToDouble(l -> l.getBbox()[3]).max().orElse(H);
            double[] bodyBbox = clampBbox(new double[] { bx0, by0, bx1, by1 }, mediaBox);

            Integer contTo = detectContinuation(text);
            String titleText = PAGE_NUM_STRIP.matcher(t.getText()).replaceAll("").trim();
            int c = assignCol(t.getX0(), splits);

            PdfArticle article = new PdfArticle();
            article.setTitle(titleText);
            article.setTitleBbox(t.getBbox());
            article.setBodyBbox(bodyBbox);
            article.setText(text);
            article.setContinuationToPage(contTo);
            article.setColumnIndex(c);
            articles.add(article);
        }

        // Sort by title y0 then x0 (reading order)
        articles.sort(Comparator.comparingDouble((PdfArticle a) -> a.getTitleBbox()[1])
                .thenComparingDouble(a -> a.getTitleBbox()[0]));

        return articles;
    }

    // ──────────────────────── article merging ────────────────────────

    /**
     * Phase 2: "N면 이어짐" 패턴으로 감지된 기사를 다음 페이지 이어지는 기사와 병합합니다.
     */
    private void mergeArticleContinuations(List<PdfArticle> articles) {
        Set<PdfArticle> toRemove = new LinkedHashSet<>();
        for (PdfArticle article : articles) {
            if (article.getContinuationToPage() == null) continue;
            int targetPage = article.getContinuationToPage();

            // 타겟 페이지에서 이어짐 마커로 시작하거나 첫 번째 기사를 찾음
            PdfArticle continuation = articles.stream()
                    .filter(a -> a.getPage() == targetPage && !toRemove.contains(a))
                    .filter(a -> {
                        String text = a.getText().trim();
                        return CONTINUED_FROM_PATTERN.matcher(text).find()
                                || text.startsWith("이어짐") || text.startsWith("이어서");
                    })
                    .findFirst()
                    .orElse(null);

            // 마커 없으면 같은 컬럼의 첫 기사 사용
            if (continuation == null) {
                continuation = articles.stream()
                        .filter(a -> a.getPage() == targetPage && !toRemove.contains(a))
                        .filter(a -> a.getColumnIndex() == article.getColumnIndex())
                        .findFirst()
                        .orElse(null);
            }

            if (continuation != null && continuation != article) {
                article.setText(article.getText().trim() + "\n" + continuation.getText().trim());
                article.setContinuationToPage(continuation.getContinuationToPage());
                toRemove.add(continuation);
                log.info("이어짐 기사 병합: {}페이지 → {}페이지 (title={})",
                        article.getPage(), targetPage, article.getTitle());
            }
        }
        articles.removeAll(toRemove);

        // ▶ 종료 마커 제거 (PDFBox 경로)
        java.util.regex.Pattern contEndPat = java.util.regex.Pattern.compile(
            "▶\\s*기사\\s*[\\d,·]+면\\s*이어짐(?:\\s*/[^\n]*)?"
        );
        // ▷ 시작 마커 제거 (PDFBox 경로: 병합된 continuation 본문 앞에 남은 마커)
        java.util.regex.Pattern contStartPat = java.util.regex.Pattern.compile(
            "▷\\s*\\d+면\\s*[‘’'“”\"](.+?)[‘’'“”\"]\\s*이어짐[^\n]*\n?",
            java.util.regex.Pattern.DOTALL
        );
        for (PdfArticle article : articles) {
            String t = article.getText();
            t = contEndPat.matcher(t).replaceAll("");
            t = contStartPat.matcher(t).replaceAll("");
            article.setText(t.trim());
        }
    }

    /**
     * Phase 3: 같은 페이지·같은 컬럼 내 200자 미만 짧은 기사 조각을 이전 기사에 병합합니다.
     */
    private void mergeShortFragments(List<PdfArticle> articles) {
        final int MIN_LENGTH = 200;
        List<PdfArticle> toRemove = new ArrayList<>();
        for (int i = 1; i < articles.size(); i++) {
            PdfArticle current = articles.get(i);
            if (current.getText().length() >= MIN_LENGTH) continue;

            PdfArticle prev = articles.get(i - 1);
            if (prev.getPage() == current.getPage()
                    && prev.getColumnIndex() == current.getColumnIndex()) {
                prev.setText(prev.getText().trim() + "\n" + current.getText().trim());
                toRemove.add(current);
                log.info("짧은 기사 조각 병합: title={} ({}자) → 이전 기사에 병합",
                        current.getTitle(), current.getText().length());
            }
        }
        articles.removeAll(toRemove);
    }

    // ──────────────────────── Upstage Hybrid ────────────────────────

    /**
     * 문제 페이지 여부를 판별합니다.
     * 아래 조건 중 하나라도 해당하면 Upstage API를 사용합니다.
     *  1. 이어짐(continued) 마커가 있는 페이지
     *  2. 와이드스팬 제목(페이지 너비 60% 초과)이 2개 이상 → 랩 레이아웃 의심
     */
    private boolean isProblematicPage(PDDocument document, int pageIndex) throws IOException {
        PDPage page = document.getPage(pageIndex);
        PdfTextExtractor.ExtractResult result = PdfTextExtractor.extractLines(page, document, pageIndex);
        List<Line> lines = result.getLines();

        double pageWidth = page.getMediaBox().getWidth();
        boolean hasContinuation = lines.stream()
                .anyMatch(l -> CONTINUATION_PATTERN.matcher(l.getText()).find()
                        || CONTINUED_FROM_PATTERN.matcher(l.getText()).find());

        long wideSpanTitleCount = lines.stream()
                .filter(l -> (l.getBbox()[2] - l.getBbox()[0]) > pageWidth * 0.6)
                .filter(l -> l.getMaxSize() >= 15.0)
                .count();

        if (hasContinuation) {
            log.debug("[Hybrid] 페이지 {} — 이어짐 마커 감지", pageIndex + 1);
            return true;
        }
        if (wideSpanTitleCount >= 2) {
            log.debug("[Hybrid] 페이지 {} — 와이드스팬 제목 {}개 감지 (랩 레이아웃 의심)",
                    pageIndex + 1, wideSpanTitleCount);
            return true;
        }
        return false;
    }

    // 청크당 목표 크기: 30MB (Upstage 허용 범위 안으로)
    private static final long CHUNK_TARGET_BYTES = 30L * 1024 * 1024;
    private static final int  MIN_CHUNK_PAGES    = 2;
    private static final int  MAX_CHUNK_PAGES    = 12;

    /**
     * Upstage API로 전체 PDF를 1회 파싱하여 전체 기사 목록을 반환합니다.
     * 413 발생 시:
     *   1차 — 페이지 분할 후 청크별 Upstage 호출 + elements 합산 처리 (이미지·레이아웃 완전 보존)
     *   2차 — 이미지 제거 후 단일 Upstage 호출 (최후 수단)
     */
    private List<PdfArticle> extractAllWithUpstage(byte[] pdfBytes) {
        // 원본 그대로 시도
        try {
            List<java.util.Map<String, Object>> elements = upstageService.parseToElements(pdfBytes);
            return upstageService.extractArticlesFromElements(elements);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (!(msg.contains("413") || msg.contains("Too Large") || msg.contains("too large"))) {
                log.error("[Upstage] API 호출 실패: {}", e.getMessage());
                return Collections.emptyList();
            }
        }

        // 1차 재시도: 페이지 분할 (이미지·좌표·레이아웃 완전 보존)
        log.warn("[Upstage] 파일 크기 초과(413), 페이지 분할 후 재시도 ({}MB)",
                pdfBytes.length / 1024 / 1024);
        try {
            List<PdfArticle> result = extractWithChunking(pdfBytes);
            if (!result.isEmpty()) return result;
            log.warn("[Upstage Chunked] 결과 없음 → 이미지 제거 폴백");
        } catch (Exception e2) {
            log.error("[Upstage Chunked] 실패: {} → 이미지 제거 폴백", e2.getMessage());
        }

        // 2차 재시도: 이미지 제거 (최후 수단)
        log.warn("[Upstage] 이미지 제거 후 최종 재시도");
        try {
            byte[] stripped = stripImages(pdfBytes);
            log.info("[Upstage] 이미지 제거 후 크기: {}MB → {}MB",
                    pdfBytes.length / 1024 / 1024, stripped.length / 1024 / 1024);
            List<java.util.Map<String, Object>> elements = upstageService.parseToElements(stripped);
            return upstageService.extractArticlesFromElements(elements);
        } catch (Exception e3) {
            log.error("[Upstage] 이미지 제거 후 재시도도 실패: {}", e3.getMessage());
        }

        return Collections.emptyList();
    }

    /**
     * PDF를 페이지 단위 청크로 분할해 청크별로 Upstage를 호출한 뒤,
     * 각 청크의 elements에 페이지 오프셋을 적용하여 전체 합산 후 기사를 추출합니다.
     *
     * 이어짐 기사 병합이 안전한 이유:
     *   mergeArticleContinuations는 페이지 번호가 아니라 ▷ 마커의 제목 텍스트로 매칭하므로
     *   오프셋이 올바르게 적용된 전체 elements 리스트에서 1회 실행하면 청크 경계와 무관하게 동작합니다.
     *
     * 페이지 오프셋:
     *   청크 i의 시작 페이지 인덱스(0-based) = i * chunkSize
     *   Upstage는 청크 내 페이지를 1부터 반환하므로 실제 신문 면수 = Upstage page + offset
     */
    private List<PdfArticle> extractWithChunking(byte[] pdfBytes) throws IOException {
        // 1단계: PDF 분할 (렌더링 없이 페이지 객체만 복사 → 빠름)
        List<byte[]> chunks  = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();

        try (org.apache.pdfbox.pdmodel.PDDocument doc =
                     org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
            int totalPages   = doc.getNumberOfPages();
            long bytesPerPage = pdfBytes.length / Math.max(1, totalPages);
            int chunkSize = (int) Math.max(MIN_CHUNK_PAGES,
                            Math.min(MAX_CHUNK_PAGES, CHUNK_TARGET_BYTES / Math.max(1, bytesPerPage)));

            log.info("[Upstage Chunked] {}MB / {}페이지 → 청크당 {}페이지 (총 {}개 청크)",
                    pdfBytes.length / 1024 / 1024, totalPages, chunkSize,
                    (int) Math.ceil((double) totalPages / chunkSize));

            for (int start = 0; start < totalPages; start += chunkSize) {
                int end = Math.min(start + chunkSize, totalPages);
                try (org.apache.pdfbox.pdmodel.PDDocument chunkDoc =
                             new org.apache.pdfbox.pdmodel.PDDocument()) {
                    for (int p = start; p < end; p++) {
                        chunkDoc.importPage(doc.getPage(p));
                    }
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    chunkDoc.save(baos);
                    chunks.add(baos.toByteArray());
                    offsets.add(start); // Upstage page 1 = 실제 신문 (start+1)면
                }
            }
        }

        // 2단계: 청크별 Upstage 호출 + 페이지 오프셋 적용
        List<java.util.Map<String, Object>> allElements = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            byte[] chunk     = chunks.get(i);
            int    pageOffset = offsets.get(i);

            log.info("[Upstage Chunked] 청크 {}/{} 처리 중 ({}MB, 오프셋 +{}페이지)",
                    i + 1, chunks.size(), chunk.length / 1024 / 1024, pageOffset);

            List<java.util.Map<String, Object>> chunkElements =
                    upstageService.parseToElements(chunk);

            // 오프셋 적용 (첫 청크는 offset=0 이므로 스킵)
            if (pageOffset > 0) {
                chunkElements.forEach(el -> {
                    Object page = el.get("page");
                    if (page instanceof Number) {
                        el.put("page", ((Number) page).intValue() + pageOffset);
                    }
                });
            }

            allElements.addAll(chunkElements);
            log.info("[Upstage Chunked] 청크 {}/{} 완료 (elements {}개, 누계 {}개)",
                    i + 1, chunks.size(), chunkElements.size(), allElements.size());
        }

        // 3단계: 전체 elements 합산 → 기사 추출 (이어짐 병합 포함)
        log.info("[Upstage Chunked] 전체 elements {}개 → 기사 추출 시작", allElements.size());
        return upstageService.extractArticlesFromElements(allElements);
    }

    /**
     * PDFBox로 PDF에서 이미지 XObject를 제거하여 파일 크기를 줄입니다.
     * 페이지 분할로도 처리 불가할 때 사용하는 최후 수단입니다.
     */
    private byte[] stripImages(byte[] pdfBytes) throws IOException {
        try (org.apache.pdfbox.pdmodel.PDDocument doc =
                     org.apache.pdfbox.Loader.loadPDF(pdfBytes);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            for (org.apache.pdfbox.pdmodel.PDPage page : doc.getPages()) {
                org.apache.pdfbox.pdmodel.PDResources res = page.getResources();
                if (res == null) continue;

                org.apache.pdfbox.cos.COSDictionary xObjects =
                        (org.apache.pdfbox.cos.COSDictionary) res.getCOSObject()
                                .getDictionaryObject(org.apache.pdfbox.cos.COSName.XOBJECT);
                if (xObjects == null) continue;

                List<org.apache.pdfbox.cos.COSName> toRemove = new ArrayList<>();
                for (org.apache.pdfbox.cos.COSName name : res.getXObjectNames()) {
                    try {
                        if (res.isImageXObject(name)) toRemove.add(name);
                    } catch (Exception ignored) {}
                }
                toRemove.forEach(xObjects::removeItem);
            }

            doc.save(baos);
            return baos.toByteArray();
        }
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

            mergeArticleContinuations(allArticles);
            mergeShortFragments(allArticles);
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
