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
    // 기사 종료 신호로 사용하는 기자명+이메일 패턴
    private static final Pattern REPORTER_EMAIL_PATTERN = Pattern.compile(
            "([\\uAC00-\\uD7A3]{2,4})\\s+([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+)"
    );

    // 광고 판별 패턴
    // 전화번호: 02-1234-5678 / 010-1234-5678 / (02)1234-5678 형태
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "\\(?\\d{2,3}\\)?[-.]\\d{3,4}[-.]\\d{4}");
    // URL
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)www\\.|http[s]?://");
    // 광고 전용 키워드 — 뉴스 기사에 거의 등장하지 않는 표현만 선별
    private static final Pattern AD_EXCLUSIVE_KEYWORD = Pattern.compile(
            "선\\s*착\\s*순|분\\s*양\\s*안\\s*내|입\\s*주\\s*문\\s*의|할\\s*인\\s*이\\s*벤\\s*트|모\\s*집\\s*공\\s*고|대표\\s*번호");
    // 명시적 광고/홍보 마커 — 하나만 있어도 즉시 광고 판정
    private static final Pattern EXPLICIT_AD_MARKER = Pattern.compile(
            "(?i)\\[광고]|\\(광고\\)|\\[PR]|\\[홍보]|\\[협찬]|\\(협찬\\)|광고문의|협찬문의");
    // 가격 패턴 — 2회 이상 등장 시 광고 신호
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "\\d+\\s*만\\s*원|\\d+[,\\d]*\\s*원\\s*[(/]|\\d+\\.?\\d*\\s*%\\s*할인");
    // 공고 패턴 — 채용·입찰·모집 공고 특유 표현
    private static final Pattern PUBLIC_NOTICE_PATTERN = Pattern.compile(
            "입\\s*찰\\s*공\\s*고|채\\s*용\\s*공\\s*고|공\\s*개\\s*모\\s*집|" +
            "지\\s*원\\s*자\\s*격|접\\s*수\\s*기\\s*간|제\\s*출\\s*서\\s*류|응\\s*시\\s*자\\s*격");
    private static final Pattern WEEKDAY_DATE_PATTERN = Pattern.compile("\\b\\d{1,2}\\s*\\(\\s*[월화수목금토일]\\s*\\)");
    private static final Pattern TIME_PATTERN = Pattern.compile("\\b(?:[01]?\\d|2[0-3]):[0-5]\\d\\b");
    private static final Pattern CLASSIFIED_KEYWORD_PATTERN = Pattern.compile(
            "주택\\s*매매|토지\\s*매매|상가|임대|전세|월세|보증금|급매|매물|구인|구직|직원\\s*구인|공인중개사");
    private static final double TITLE_RATIO_THRESHOLD_NON_BOLD = 1.24;
    private static final double TITLE_RATIO_THRESHOLD_BOLD = 1.12;
    private static final double TITLE_ABS_THRESHOLD_NON_BOLD = 11.0;
    private static final double TITLE_ABS_THRESHOLD_BOLD = 10.3;
    private static final double STRONG_HEADLINE_RATIO = 1.58;
    private static final double STRONG_HEADLINE_MIN_WIDTH_RATIO = 0.34;
    private static final Pattern SUBHEADING_MARKER_PATTERN = Pattern.compile("^[■◆▲●◇□▪ㆍ·].*");
    // 안정화 모드: 과분리/과합침을 유발할 수 있는 실험적 보정은 기본 OFF
    private static final boolean ENABLE_SINGLE_COLUMN_SPLIT_PATCH = true;
    private static final boolean ENABLE_SUBCOLUMN_BODY_FILTER = true;
    private static final boolean ENABLE_INTERNAL_HEADLINE_SPLIT = true;

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
            List<PdfArticle> allArticles = new ArrayList<>();
            int totalPages = document.getNumberOfPages();
            // 페이지별 내장 이미지 목록 보관 맵
            Map<Integer, List<ImagePositionExtractor.ImageBoundingBox>> pageImagesMap = new HashMap<>();
            ImagePositionExtractor imageExtractor = new ImagePositionExtractor();

            for (int pi = 0; pi < totalPages; pi++) {
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
                if (progressCallback != null) {
                    progressCallback.accept(pi + 1, totalPages);
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

            return new ExtractionResult(sb.toString(), images, allArticles);
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
        private final List<PdfArticle> articles;

        public ExtractionResult(String text, List<ArticleImageData> articleImages, List<PdfArticle> articles) {
            this.text = text;
            this.articleImages = articleImages;
            this.articles = articles;
        }

        public String getText() { return text; }
        public List<ArticleImageData> getArticleImages() { return articleImages; }
        public List<PdfArticle> getArticles() { return articles; }
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
     * 즉시 판정: 명시적 광고 마커([광고], [홍보], [협찬] 등)
     * 점수 기반 판정 (임계값 4점):
     *   - 전화번호 패턴: 2점
     *   - URL 패턴: 2점
     *   - 광고 전용 키워드(분양안내·선착순 등): 3점
     *   - 공고 패턴(채용공고·입찰공고 등): 2점
     *   - 가격 표현 2회 이상: 2점
     *   - AD_KEYWORDS 3개 이상: 1점
     */
    private boolean isLikelyAd(String body) {
        if (EXPLICIT_AD_MARKER.matcher(body).find()) return true;
        if (isStrongTimetableAd(body)) return true;

        int score = 0;
        if (PHONE_PATTERN.matcher(body).find())        score += 2;
        if (URL_PATTERN.matcher(body).find())          score += 2;
        if (AD_EXCLUSIVE_KEYWORD.matcher(body).find()) score += 3;
        if (PUBLIC_NOTICE_PATTERN.matcher(body).find()) score += 2;

        long priceMatches = PRICE_PATTERN.matcher(body).results().count();
        if (priceMatches >= 2) score += 2;

        long adKwCount = AD_KEYWORDS.stream().filter(body::contains).count();
        if (adKwCount >= 3) score += 1;

        return score >= 4;
    }

    /**
     * 일반 기사의 단발성 시간 언급은 허용하고,
     * 날짜(요일)+시각이 반복되는 표 형태일 때만 광고/시간표로 판정합니다.
     */
    private boolean isStrongTimetableAd(String body) {
        int weekdayDateCount = (int) WEEKDAY_DATE_PATTERN.matcher(body).results().count();
        int timeCount = (int) TIME_PATTERN.matcher(body).results().count();
        long structuredLines = Arrays.stream(body.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> line.length() <= 40)
                .filter(line -> WEEKDAY_DATE_PATTERN.matcher(line).find() || TIME_PATTERN.matcher(line).find())
                .count();
        long totalLines = Arrays.stream(body.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .count();
        double ratio = totalLines == 0 ? 0.0 : (double) structuredLines / totalLines;
        return (weekdayDateCount >= 4 && timeCount >= 8) || (timeCount >= 12 && ratio >= 0.6);
    }

    /**
     * 장터/매물/구인 중심의 분류면 성격 페이지는 통째로 분리 대상에서 제외합니다.
     */
    private boolean isLikelyClassifiedPage(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        String merged = lines.stream().map(Line::getText).collect(Collectors.joining("\n"));
        int phoneCount = (int) PHONE_PATTERN.matcher(merged).results().count();
        int keywordCount = (int) CLASSIFIED_KEYWORD_PATTERN.matcher(merged).results().count();
        long bulletLines = lines.stream()
                .map(Line::getText)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(t -> t.startsWith("◆") || t.startsWith("■") || t.startsWith("☎"))
                .count();

        return phoneCount >= 3 && keywordCount >= 4 && bulletLines >= 6;
    }

    // ──────────────────────── title detection ────────────────────────

    private boolean isProbableTitle(Line line, double bodyMedian) {
        String t = line.getText();

        if (t.length() < 3 || t.length() > 80)
            return false;

        // 비볼드: 원래 검증된 1.38 배율 유지 (소제목 오인식 방지)
        // 볼드:   1.15 배율 허용 (볼드 자체가 강한 제목 신호이므로 완화)
        double ratioThreshold = line.isBold() ? TITLE_RATIO_THRESHOLD_BOLD : TITLE_RATIO_THRESHOLD_NON_BOLD;
        double absThreshold   = line.isBold() ? TITLE_ABS_THRESHOLD_BOLD : TITLE_ABS_THRESHOLD_NON_BOLD;
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
        if (SUBHEADING_MARKER_PATTERN.matcher(t).matches())
            return false;

        // 기자명+이메일 줄은 제목이 아니라 기사 종료부이므로 제외
        if (t.contains("@") || REPORTER_EMAIL_PATTERN.matcher(t).find())
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

    /**
     * 본문 bbox 안에 다른 기사 컬럼 꼬리 라인이 섞일 때,
     * 제목이 시작한 하위 컬럼(anchor) 기준으로 라인을 재필터링합니다.
     */
    private List<Line> filterBodyLinesByAnchorSubColumn(
            List<Line> bodyLines,
            double articleX0,
            double articleX1,
            double titleX0,
            boolean allowRightAdjacentSubColumn
    ) {
        if (bodyLines.size() < 8) {
            return bodyLines;
        }

        List<Double> xs = bodyLines.stream().map(Line::getX0).collect(Collectors.toList());
        double localWidth = Math.max(1.0, articleX1 - articleX0);
        List<Double> localSplits = inferColumnSplits(xs, localWidth);
        if (localSplits.isEmpty()) {
            return bodyLines;
        }

        int anchorCol = assignCol(titleX0, localSplits);
        List<Line> filtered = bodyLines.stream()
                .filter(line -> {
                    int c = assignCol(line.getX0(), localSplits);
                    if (c == anchorCol) {
                        return true;
                    }
                    return allowRightAdjacentSubColumn && c == anchorCol + 1;
                })
                .collect(Collectors.toList());

        // 과필터링 방지
        return filtered.size() >= 4 ? filtered : bodyLines;
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

    private double horizontalOverlapRatio(Line a, Line b) {
        double ax0 = a.getBbox()[0], ax1 = a.getBbox()[2];
        double bx0 = b.getBbox()[0], bx1 = b.getBbox()[2];
        double inter = Math.max(0, Math.min(ax1, bx1) - Math.max(ax0, bx0));
        double minWidth = Math.max(1.0, Math.min(ax1 - ax0, bx1 - bx0));
        return inter / minWidth;
    }

    /**
     * 본문 중간의 볼드 소제목 오탐을 줄이기 위한 필터.
     * 메인 제목은 보통 위/아래 여백이 크고, 소제목은 본문에 촘촘히 끼어 있는 경우가 많다는 점을 이용합니다.
     */
    private boolean isLikelyEmbeddedSubheading(Line candidate, List<Line> lines, double bodyMedian, double pageWidth) {
        // 매우 강한 제목 형태는 소제목으로 오판하지 않도록 보호
        if (isStrongHeadlineShape(candidate, bodyMedian, pageWidth)) {
            return false;
        }

        double size = candidate.getMaxSize();
        boolean onlySlightlyBigger = size < bodyMedian * 1.34;
        boolean narrowWidth = candidate.getWidth() < pageWidth * 0.45;

        Line nearestAbove = null;
        Line nearestBelow = null;
        for (Line line : lines) {
            if (line == candidate) {
                continue;
            }
            if (horizontalOverlapRatio(line, candidate) < 0.45) {
                continue;
            }

            if (line.getY1() <= candidate.getY0()) {
                if (nearestAbove == null || line.getY1() > nearestAbove.getY1()) {
                    nearestAbove = line;
                }
            } else if (line.getY0() >= candidate.getY1()) {
                if (nearestBelow == null || line.getY0() < nearestBelow.getY0()) {
                    nearestBelow = line;
                }
            }
        }

        if (nearestAbove == null || nearestBelow == null) {
            return false;
        }

        double topGap = candidate.getY0() - nearestAbove.getY1();
        double bottomGap = nearestBelow.getY0() - candidate.getY1();
        boolean tightlyEmbedded = topGap >= 0 && bottomGap >= 0
                && topGap < bodyMedian * 1.25
                && bottomGap < bodyMedian * 1.45;

        return onlySlightlyBigger && narrowWidth && tightlyEmbedded;
    }

    private boolean isStrongHeadlineShape(Line candidate, double bodyMedian, double pageWidth) {
        if (candidate == null) {
            return false;
        }
        String text = candidate.getText() == null ? "" : candidate.getText().trim();
        if (text.length() < 8) {
            return false;
        }
        boolean sizeStrong = candidate.getMaxSize() >= bodyMedian * STRONG_HEADLINE_RATIO;
        boolean widthStrong = candidate.getWidth() >= pageWidth * STRONG_HEADLINE_MIN_WIDTH_RATIO;
        return sizeStrong && widthStrong;
    }

    private double[] unionBbox(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return new double[] { 0, 0, 0, 0 };
        }
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE;
        double x1 = 0, y1 = 0;
        for (Line l : lines) {
            x0 = Math.min(x0, l.getBbox()[0]);
            y0 = Math.min(y0, l.getBbox()[1]);
            x1 = Math.max(x1, l.getBbox()[2]);
            y1 = Math.max(y1, l.getBbox()[3]);
        }
        return new double[] { x0, y0, x1, y1 };
    }

    private int findInternalHeadlineSplitIndex(List<Line> ordered, double bodyMedian, double pageWidth) {
        if (ordered == null || ordered.size() < 10) {
            return -1;
        }

        for (int i = 3; i < ordered.size() - 3; i++) {
            Line line = ordered.get(i);
            String text = line.getText() == null ? "" : line.getText().trim();
            if (text.length() < 8 || text.length() > 70) {
                continue;
            }
            if (SUBHEADING_MARKER_PATTERN.matcher(text).matches()) {
                continue;
            }
            if (REPORTER_EMAIL_PATTERN.matcher(text).find()) {
                continue;
            }

            if (text.endsWith("기자") || text.endsWith("보도자료")) {
                continue;
            }

            boolean strongSize = line.getMaxSize() >= bodyMedian * 1.40
                    || (line.isBold() && line.getMaxSize() >= bodyMedian * 1.28);
            boolean strongWidth = line.getWidth() >= pageWidth * 0.25;
            if (!strongSize || !strongWidth) {
                continue;
            }

            Line prev = ordered.get(i - 1);
            Line next = ordered.get(i + 1);
            double topGap = line.getY0() - prev.getY1();
            double bottomGap = next.getY0() - line.getY1();
            boolean separated = topGap >= bodyMedian * 0.45 && bottomGap >= bodyMedian * 0.45;
            if (!separated) {
                continue;
            }

            int beforeChars = ordered.subList(0, i).stream()
                    .map(Line::getText)
                    .filter(Objects::nonNull)
                    .mapToInt(String::length)
                    .sum();
            int afterChars = ordered.subList(i + 1, ordered.size()).stream()
                    .map(Line::getText)
                    .filter(Objects::nonNull)
                    .mapToInt(String::length)
                    .sum();
            if (beforeChars < 80 || afterChars < 80) {
                continue;
            }

            return i;
        }
        return -1;
    }

    private boolean isLikelyNewArticleTitleAfterReporter(Line line, double bodyMedian, double pageWidth) {
        if (line == null) {
            return false;
        }
        String text = line.getText() == null ? "" : line.getText().trim();
        if (text.length() < 8 || text.length() > 90) {
            return false;
        }
        if (text.startsWith("▶") || text.startsWith("▷")) {
            return false;
        }
        if (SUBHEADING_MARKER_PATTERN.matcher(text).matches()) {
            return false;
        }
        if (REPORTER_EMAIL_PATTERN.matcher(text).find() || text.contains("@")) {
            return false;
        }

        boolean strongBySize = line.getMaxSize() >= bodyMedian * 1.33;
        boolean strongByBold = line.isBold() && line.getMaxSize() >= bodyMedian * 1.24;
        boolean enoughWidth = line.getWidth() >= pageWidth * 0.22;
        return (strongBySize || strongByBold) && enoughWidth;
    }

    private int findReporterBoundaryIndex(List<Line> ordered) {
        int last = -1;
        for (int i = 0; i < ordered.size(); i++) {
            String text = ordered.get(i).getText();
            if (text != null && REPORTER_EMAIL_PATTERN.matcher(text).find()) {
                last = i;
            }
        }
        return last;
    }

    private int findFirstStrongTitleInTail(List<Line> lines, int start, double bodyMedian, double pageWidth) {
        for (int i = Math.max(0, start); i < lines.size(); i++) {
            if (isLikelyNewArticleTitleAfterReporter(lines.get(i), bodyMedian, pageWidth)) {
                return i;
            }
        }
        return -1;
    }

    private List<Line> filterMainTitleCandidates(
            List<Line> titleCandidates,
            List<Line> allLines,
            double bodyMedian,
            double pageWidth
    ) {
        if (titleCandidates.isEmpty()) {
            return titleCandidates;
        }

        List<Line> filtered = titleCandidates.stream()
                .filter(t -> !isLikelyEmbeddedSubheading(t, allLines, bodyMedian, pageWidth))
                .collect(Collectors.toList());

        // 본문 중간 독립 기사의 강한 제목이 과하게 제거된 경우 복원
        filtered = restoreStandaloneDroppedTitles(titleCandidates, filtered, allLines, bodyMedian, pageWidth);

        // 컬럼 맨 위의 독립 제목은 소제목 필터에서 빠지지 않도록 보호
        filtered = restoreTopTitlesPerColumn(titleCandidates, filtered, pageWidth);

        // 과필터링 방지: 전부 제거되면 원래 후보를 사용
        return filtered.isEmpty() ? titleCandidates : filtered;
    }

    private boolean isRelaxedUpperHeadlineCandidate(Line line, double bodyMedian, double pageWidth) {
        String text = line.getText() == null ? "" : line.getText().trim();
        if (text.length() < 8 || text.length() > 80) {
            return false;
        }
        if (text.startsWith("▶") || text.startsWith("▷")) {
            return false;
        }
        if (SUBHEADING_MARKER_PATTERN.matcher(text).matches()) {
            return false;
        }
        if (REPORTER_EMAIL_PATTERN.matcher(text).find() || text.contains("@")) {
            return false;
        }

        boolean sizeEnough = line.getMaxSize() >= bodyMedian * 1.14
                || (line.isBold() && line.getMaxSize() >= bodyMedian * 1.05);
        boolean widthEnough = line.getWidth() >= pageWidth * 0.18;
        return sizeEnough && widthEnough;
    }

    private List<Line> augmentMissingUpperHeadlinePerColumn(
            List<Line> titles,
            List<Line> allLines,
            double bodyMedian,
            double pageWidth,
            double pageHeight
    ) {
        if (allLines.isEmpty()) {
            return titles;
        }

        List<Double> xsAll = allLines.stream().map(Line::getX0).collect(Collectors.toList());
        List<Double> splits = inferColumnSplits(xsAll, pageWidth);
        int colCount = splits.size() + 1;

        Map<Integer, List<Line>> titleByCol = new HashMap<>();
        for (Line t : titles) {
            int col = assignCol(t.getX0(), splits);
            titleByCol.computeIfAbsent(col, k -> new ArrayList<>()).add(t);
        }
        titleByCol.values().forEach(list -> list.sort(Comparator.comparingDouble(Line::getY0)));

        Set<Line> augmented = new LinkedHashSet<>(titles);
        double upperBand = pageHeight * 0.36;

        for (int col = 0; col < colCount; col++) {
            List<Line> colTitles = titleByCol.getOrDefault(col, Collections.emptyList());
            double firstTitleY = colTitles.isEmpty() ? Double.MAX_VALUE : colTitles.get(0).getY0();
            if (firstTitleY <= upperBand) {
                continue;
            }

            Line best = null;
            for (Line line : allLines) {
                if (assignCol(line.getX0(), splits) != col) {
                    continue;
                }
                if (line.getY0() > upperBand) {
                    continue;
                }
                if (!isRelaxedUpperHeadlineCandidate(line, bodyMedian, pageWidth)) {
                    continue;
                }
                if (best == null || line.getY0() < best.getY0()) {
                    best = line;
                }
            }
            if (best != null) {
                augmented.add(best);
            }
        }

        List<Line> result = new ArrayList<>(augmented);
        result.sort(Comparator.comparingDouble(Line::getY0).thenComparingDouble(Line::getX0));
        return result;
    }

    private List<Line> restoreTopTitlesPerColumn(
            List<Line> originalCandidates,
            List<Line> filteredCandidates,
            double pageWidth
    ) {
        if (originalCandidates.isEmpty()) {
            return filteredCandidates;
        }

        List<Double> xs = originalCandidates.stream().map(Line::getX0).collect(Collectors.toList());
        List<Double> splits = inferColumnSplits(xs, pageWidth);

        Map<Integer, Line> topByColumn = new HashMap<>();
        for (Line line : originalCandidates) {
            int col = assignCol(line.getX0(), splits);
            Line current = topByColumn.get(col);
            if (current == null
                    || line.getY0() < current.getY0()
                    || (Math.abs(line.getY0() - current.getY0()) < 1.0 && line.getX0() < current.getX0())) {
                topByColumn.put(col, line);
            }
        }

        Set<Line> restored = new LinkedHashSet<>(filteredCandidates);
        restored.addAll(topByColumn.values());

        List<Line> result = new ArrayList<>(restored);
        result.sort(Comparator.comparingDouble(Line::getY0).thenComparingDouble(Line::getX0));
        return result;
    }

    /**
     * 신문 지면에서 2줄(또는 3줄)로 분리된 제목을 1개의 제목 블록으로 병합한다.
     */
    private List<Line> mergeStackedTitleCandidates(List<Line> titles, double bodyMedian, double pageWidth) {
        if (titles == null || titles.size() < 2) {
            return titles == null ? Collections.emptyList() : titles;
        }

        List<Line> sorted = new ArrayList<>(titles);
        sorted.sort(Comparator.comparingDouble(Line::getY0).thenComparingDouble(Line::getX0));

        List<Line> merged = new ArrayList<>();
        boolean[] used = new boolean[sorted.size()];

        for (int i = 0; i < sorted.size(); i++) {
            if (used[i]) {
                continue;
            }
            Line base = sorted.get(i);
            used[i] = true;

            StringBuilder text = new StringBuilder(base.getText() == null ? "" : base.getText().trim());
            double[] bbox = Arrays.copyOf(base.getBbox(), 4);
            double maxSize = base.getMaxSize();
            boolean bold = base.isBold();
            Line anchor = base;

            for (int j = i + 1; j < sorted.size(); j++) {
                if (used[j]) {
                    continue;
                }

                Line next = sorted.get(j);
                double gap = next.getY0() - anchor.getY1();
                if (gap > bodyMedian * 2.4) {
                    break;
                }
                if (gap < -bodyMedian * 0.6) {
                    continue;
                }

                double overlap = horizontalOverlapRatio(anchor, next);
                double leftDelta = Math.abs(anchor.getX0() - next.getX0());
                double sizeRatio = Math.min(anchor.getMaxSize(), next.getMaxSize())
                        / Math.max(anchor.getMaxSize(), next.getMaxSize());

                boolean closeInX = leftDelta <= pageWidth * 0.045;
                boolean similarSize = sizeRatio >= 0.80;
                if (!(overlap >= 0.50 || closeInX) || !similarSize) {
                    continue;
                }

                used[j] = true;
                String nextText = next.getText() == null ? "" : next.getText().trim();
                if (!nextText.isEmpty()) {
                    if (text.length() > 0) {
                        text.append("\n");
                    }
                    text.append(nextText);
                }
                bbox[0] = Math.min(bbox[0], next.getBbox()[0]);
                bbox[1] = Math.min(bbox[1], next.getBbox()[1]);
                bbox[2] = Math.max(bbox[2], next.getBbox()[2]);
                bbox[3] = Math.max(bbox[3], next.getBbox()[3]);
                maxSize = Math.max(maxSize, next.getMaxSize());
                bold = bold || next.isBold();
                anchor = next;
            }

            merged.add(new Line(text.toString().trim(), bbox, maxSize, bold));
        }

        merged.sort(Comparator.comparingDouble(Line::getY0).thenComparingDouble(Line::getX0));
        return merged;
    }

    private List<Line> restoreStandaloneDroppedTitles(
            List<Line> originalCandidates,
            List<Line> filteredCandidates,
            List<Line> allLines,
            double bodyMedian,
            double pageWidth
    ) {
        Set<Line> kept = new LinkedHashSet<>(filteredCandidates);
        for (Line candidate : originalCandidates) {
            if (kept.contains(candidate)) {
                continue;
            }
            if (isLikelyStandaloneTitleBlock(candidate, allLines, bodyMedian, pageWidth)) {
                kept.add(candidate);
            }
        }
        List<Line> result = new ArrayList<>(kept);
        result.sort(Comparator.comparingDouble(Line::getY0).thenComparingDouble(Line::getX0));
        return result;
    }

    private boolean isLikelyStandaloneTitleBlock(
            Line candidate,
            List<Line> allLines,
            double bodyMedian,
            double pageWidth
    ) {
        String text = candidate.getText() == null ? "" : candidate.getText().trim();
        if (text.length() < 9 || text.length() > 80) {
            return false;
        }
        if (SUBHEADING_MARKER_PATTERN.matcher(text).matches()) {
            return false;
        }

        boolean strongSize = candidate.getMaxSize() >= bodyMedian * 1.42
                || (candidate.isBold() && candidate.getMaxSize() >= bodyMedian * 1.30);
        boolean enoughWidth = candidate.getWidth() >= pageWidth * 0.25;
        if (!strongSize || !enoughWidth) {
            return false;
        }

        Line nearestAbove = null;
        Line nearestBelow = null;
        for (Line line : allLines) {
            if (line == candidate || horizontalOverlapRatio(line, candidate) < 0.40) {
                continue;
            }
            if (line.getY1() <= candidate.getY0()) {
                if (nearestAbove == null || line.getY1() > nearestAbove.getY1()) {
                    nearestAbove = line;
                }
            } else if (line.getY0() >= candidate.getY1()) {
                if (nearestBelow == null || line.getY0() < nearestBelow.getY0()) {
                    nearestBelow = line;
                }
            }
        }
        if (nearestAbove == null || nearestBelow == null) {
            return false;
        }

        double topGap = candidate.getY0() - nearestAbove.getY1();
        double bottomGap = nearestBelow.getY0() - candidate.getY1();
        return topGap >= bodyMedian * 1.55 && bottomGap >= bodyMedian * 1.0;
    }

    private boolean hasCompetingTitleNearTop(
            int columnIndex,
            double y0,
            double bodyMedian,
            Map<Integer, List<Line>> titlesByCol
    ) {
        List<Line> colTitles = titlesByCol.getOrDefault(columnIndex, Collections.emptyList());
        double thresholdY = y0 + bodyMedian * 6.0;
        for (Line title : colTitles) {
            if (title.getY0() > y0 && title.getY0() <= thresholdY) {
                return true;
            }
        }
        return false;
    }

    /**
     * 기사 제목 아래에 인접 컬럼 본문이 함께 시작되는 경우(좌/우 2단 기사)를 감지합니다.
     */
    private boolean shouldExpandToRightAdjacentColumn(
            Line title,
            int columnIndex,
            List<Double> splits,
            List<Line> allLines,
            Map<Integer, List<Line>> titlesByCol,
            double bodyMedian
    ) {
        int lastCol = splits.size();
        if (columnIndex >= lastCol) {
            return false;
        }

        double colX0 = (columnIndex == 0) ? 0 : splits.get(columnIndex - 1);
        double colX1 = splits.get(columnIndex);
        double colWidth = Math.max(1.0, colX1 - colX0);
        double titleWidth = title.getWidth();

        // 메인 제목이 현재 컬럼에서 충분히 넓게 잡히지 않으면 확장하지 않음
        if (titleWidth < colWidth * 0.72) {
            return false;
        }

        int rightCol = columnIndex + 1;
        double rightX0 = splits.get(columnIndex);
        double rightX1 = (rightCol == splits.size()) ? Double.MAX_VALUE : splits.get(rightCol);
        double probeTop = title.getY1();
        double probeBottom = probeTop + bodyMedian * 10.0;
        double titleY0 = title.getY0();

        long rightBodyLines = allLines.stream()
                .filter(l -> l.getX0() >= rightX0 && l.getX0() < rightX1)
                .filter(l -> l.getY0() >= probeTop && l.getY0() <= probeBottom)
                .filter(l -> l.getMaxSize() <= bodyMedian * 1.20)
                .filter(l -> l.getText() != null && l.getText().trim().length() >= 4)
                .count();

        // 오른쪽 컬럼 상단에 경쟁 제목이 있으면 해당 컬럼은 다른 기사일 확률이 큼
        if (hasCompetingTitleNearTop(rightCol, probeTop, bodyMedian, titlesByCol)) {
            return false;
        }
        // 확장 예상 구간(제목 주변 포함)에 오른쪽 컬럼의 다른 제목이 있으면 확장을 금지
        double rightGuardTop = titleY0 - bodyMedian * 2.0;
        double rightGuardBottom = probeTop + bodyMedian * 18.0;
        boolean hasRightCompetingTitle = titlesByCol.getOrDefault(rightCol, Collections.emptyList()).stream()
                .anyMatch(t -> t != title && t.getY0() >= rightGuardTop && t.getY0() <= rightGuardBottom);
        if (hasRightCompetingTitle) {
            return false;
        }

        // 현재 제목이 컬럼 경계 근처까지 실제로 확장된 형태가 아니면 우측 확장을 하지 않음
        boolean reachesBoundary = title.getBbox()[2] >= colX1 - colWidth * 0.08;
        if (!reachesBoundary) {
            return false;
        }

        return rightBodyLines >= 3;
    }

    private double findNextTitleTopInColumn(List<Line> colTitles, double currentTitleY) {
        double next = Double.MAX_VALUE;
        for (Line t : colTitles) {
            if (t.getY0() > currentTitleY && t.getY0() < next) {
                next = t.getY0();
            }
        }
        return next;
    }

    /**
     * 제목들이 전부 하나의 컬럼으로 오인식됐지만 x축으로 크게 벌어져 있으면
     * 가장 큰 간격 지점에 임시 split을 추가해 좌/우 기사 분리를 보정합니다.
     */
    private List<Double> patchSplitsIfSingleColumnMisdetected(List<Line> titles, List<Double> splits, double pageWidth) {
        if (titles == null || titles.size() < 2) {
            return splits;
        }

        // 현재 split 기준으로도 제목이 한 컬럼으로만 매핑될 때만 보정
        Set<Integer> mappedCols = titles.stream()
                .map(t -> assignCol(t.getX0(), splits))
                .collect(Collectors.toSet());
        if (mappedCols.size() > 1) {
            return splits;
        }

        List<Double> xs = titles.stream()
                .map(Line::getX0)
                .sorted()
                .collect(Collectors.toList());
        double maxGap = 0.0;
        int maxGapIdx = -1;
        for (int i = 0; i < xs.size() - 1; i++) {
            double gap = xs.get(i + 1) - xs.get(i);
            if (gap > maxGap) {
                maxGap = gap;
                maxGapIdx = i;
            }
        }

        // 지면 폭 대비 충분히 큰 간격일 때만 분할(보수적)
        if (maxGapIdx < 0 || maxGap < pageWidth * 0.18) {
            return splits;
        }

        double newSplit = (xs.get(maxGapIdx) + xs.get(maxGapIdx + 1)) / 2.0;
        List<Double> patched = new ArrayList<>(splits);
        patched.add(newSplit);
        patched = patched.stream()
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        return patched;
    }

    private boolean isInsideBboxCenter(Line line, double[] bbox) {
        double cx = (line.getBbox()[0] + line.getBbox()[2]) / 2.0;
        double cy = (line.getBbox()[1] + line.getBbox()[3]) / 2.0;
        return cx >= bbox[0] && cx <= bbox[2] && cy >= bbox[1] && cy <= bbox[3];
    }

    private boolean overlapsArticleTitleArea(Line line, PdfArticle article) {
        if (article.getTitleBbox() == null) {
            return false;
        }
        double[] lb = line.getBbox();
        double[] tb = article.getTitleBbox();
        double interX = Math.max(0, Math.min(lb[2], tb[2]) - Math.max(lb[0], tb[0]));
        double minW = Math.max(1.0, Math.min(lb[2] - lb[0], tb[2] - tb[0]));
        double overlapRatio = interX / minW;
        boolean yNear = lb[1] <= tb[3] + 2 && lb[3] >= tb[1] - 2;
        return overlapRatio >= 0.5 && yNear;
    }

    /**
     * 한 줄이 여러 기사에 중복으로 들어가지 않도록, 가장 가까운 기사 하나에만 귀속시킵니다.
     */
    private List<PdfArticle> enforceUniqueBodyLineOwnership(
            List<PdfArticle> articles,
            List<Line> allLines,
            double pageWidth,
            List<Double> pageSplits,
            double bodyMedian
    ) {
        if (articles == null || articles.isEmpty() || allLines == null || allLines.isEmpty()) {
            return articles;
        }

        Map<PdfArticle, List<Line>> owned = new LinkedHashMap<>();
        for (PdfArticle article : articles) {
            owned.put(article, new ArrayList<>());
        }

        for (Line line : allLines) {
            String text = line.getText();
            if (text == null || text.trim().isEmpty()) {
                continue;
            }
            // 제목으로 보이는 라인이 본문으로 흘러들어가 중복되는 현상 방지
            if (isProbableTitle(line, bodyMedian)) {
                continue;
            }

            int lineCol = assignCol(line.getX0(), pageSplits);
            List<PdfArticle> candidates = new ArrayList<>();
            for (PdfArticle article : articles) {
                if (article.getBodyBbox() == null || article.getTitleBbox() == null) {
                    continue;
                }
                if (!isAllowedColumnForArticleLine(article, lineCol, pageSplits, pageWidth)) {
                    continue;
                }
                if (!isInsideBboxCenter(line, article.getBodyBbox())) {
                    continue;
                }
                // 제목 영역 라인은 본문 귀속에서 제외
                if (overlapsArticleTitleArea(line, article)) {
                    continue;
                }
                // 제목보다 위 라인은 제외
                if (line.getY0() < article.getTitleBbox()[3] - 2) {
                    continue;
                }
                candidates.add(article);
            }

            if (candidates.isEmpty()) {
                continue;
            }

            PdfArticle best = null;
            double bestScore = Double.MAX_VALUE;
            double lineCy = (line.getBbox()[1] + line.getBbox()[3]) / 2.0;

            for (PdfArticle article : candidates) {
                double titleBottom = article.getTitleBbox()[3];
                double bodyBottom = article.getBodyBbox()[3];
                double verticalScore = Math.abs(lineCy - Math.max(titleBottom, Math.min(lineCy, bodyBottom)));
                double colPenalty = (lineCol == article.getColumnIndex()) ? 0.0 : 120.0;
                double score = verticalScore + colPenalty;
                if (score < bestScore) {
                    bestScore = score;
                    best = article;
                }
            }

            if (best != null) {
                owned.get(best).add(line);
            }
        }

        for (PdfArticle article : articles) {
            List<Line> lines = owned.getOrDefault(article, List.of());
            if (lines.size() < 3) {
                continue;
            }
            double[] articleBodyBbox = article.getBodyBbox();
            double[] articleTitleBbox = article.getTitleBbox();
            if (articleBodyBbox != null && articleTitleBbox != null) {
                boolean allowRightAdjacent = isArticleExpandedToRight(article, pageSplits, pageWidth);
                lines = filterBodyLinesByAnchorSubColumn(
                        lines,
                        articleBodyBbox[0],
                        articleBodyBbox[2],
                        articleTitleBbox[0],
                        allowRightAdjacent
                );
            }
            List<Line> ordered = sortLinesReadingOrder(lines, pageWidth);
            String rebuilt = ordered.stream().map(Line::getText).collect(Collectors.joining("\n")).trim();
            if (rebuilt.length() < 50) {
                continue;
            }
            article.setText(rebuilt);
            article.setBodyBbox(unionBbox(ordered));
        }

        return articles;
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
        if (isLikelyClassifiedPage(lines)) {
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
        titles = filterMainTitleCandidates(titles, lines, bodyMedian, W);
        titles = augmentMissingUpperHeadlinePerColumn(titles, lines, bodyMedian, W, H);
        titles = mergeStackedTitleCandidates(titles, bodyMedian, W);

        if (titles.isEmpty()) {
            return Collections.emptyList();
        }

        // Infer page-level column splits
        List<Double> xsTitle = titles.stream().map(Line::getX0).collect(Collectors.toList());
        List<Double> xsAll = lines.stream().map(Line::getX0).collect(Collectors.toList());
        List<Double> xsForSplits = xsTitle.size() >= 8 ? xsTitle : xsAll;
        List<Double> splits = inferColumnSplits(xsForSplits, W);
        if (ENABLE_SINGLE_COLUMN_SPLIT_PATCH) {
            splits = patchSplitsIfSingleColumnMisdetected(titles, splits, W);
        }

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
                boolean expandedToRight = false;
                if (titleWidth > W * 0.4) {
                    artX0 = Math.max(0, t.getBbox()[0] - margin);
                    artX1 = Math.min(W, t.getBbox()[2] + margin);
                } else {
                    artX0 = colX0;
                    artX1 = colX1;
                }

                // 좌/우 2단짜리 단일 기사인 경우 오른쪽 인접 컬럼까지 본문 범위를 확장
                if (shouldExpandToRightAdjacentColumn(t, c, splits, lines, titlesByCol, bodyMedian)) {
                    int rightCol = c + 1;
                    double rightColX1 = (rightCol == splits.size()) ? W : splits.get(rightCol);
                    artX1 = Math.max(artX1, Math.min(W, rightColX1 + margin));
                    expandedToRight = true;

                    double nextInCurrent = findNextTitleTopInColumn(titlesByCol.getOrDefault(c, Collections.emptyList()), t.getY0());
                    double nextInRight = findNextTitleTopInColumn(titlesByCol.getOrDefault(rightCol, Collections.emptyList()), t.getY0());
                    double nextTop = Math.min(nextInCurrent, nextInRight);
                    if (nextTop != Double.MAX_VALUE) {
                        y1 = Math.min(y1, nextTop - 2);
                    }
                }

                double[] bodyBbox = clampBbox(new double[] { artX0, y0, artX1, y1 }, mediaBox);

                List<Line> bodyLines = linesInBbox(lines, bodyBbox);
                if (ENABLE_SUBCOLUMN_BODY_FILTER) {
                    bodyLines = filterBodyLinesByAnchorSubColumn(
                            bodyLines,
                            artX0,
                            artX1,
                            t.getX0(),
                            expandedToRight
                    );
                }
                List<Line> ordered = sortLinesReadingOrder(bodyLines, W);
                int reporterIdx = findReporterBoundaryIndex(ordered);
                int forcedSplitIdx = -1;
                if (reporterIdx >= 0 && reporterIdx < ordered.size() - 6) {
                    forcedSplitIdx = findFirstStrongTitleInTail(ordered, reporterIdx + 1, bodyMedian, W);
                }

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

                // 기자줄 이후에 새 강한 제목이 등장하면 이전 기사 오염으로 보고 강제 분리
                if (forcedSplitIdx > 0) {
                    List<Line> part1 = ordered.subList(0, reporterIdx + 1);
                    Line splitTitle = ordered.get(forcedSplitIdx);
                    List<Line> part2Body = ordered.subList(forcedSplitIdx + 1, ordered.size());

                    String part1Text = part1.stream().map(Line::getText).collect(Collectors.joining("\n")).trim();
                    String part2Text = part2Body.stream().map(Line::getText).collect(Collectors.joining("\n")).trim();

                    if (part1Text.length() >= 120 && part2Text.length() >= 120) {
                        if (!isLikelyAd(part1Text)) {
                            PdfArticle a1 = new PdfArticle();
                            a1.setTitle(titleText);
                            a1.setTitleBbox(t.getBbox());
                            a1.setBodyBbox(unionBbox(part1));
                            a1.setText(part1Text);
                            a1.setContinuationToPage(detectContinuation(part1Text));
                            a1.setColumnIndex(c);
                            articles.add(a1);
                        }

                        String secondTitle = PAGE_NUM_STRIP.matcher(splitTitle.getText()).replaceAll("").trim();
                        if (!isLikelyAd(part2Text)) {
                            PdfArticle a2 = new PdfArticle();
                            a2.setTitle(secondTitle);
                            a2.setTitleBbox(splitTitle.getBbox());
                            a2.setBodyBbox(unionBbox(part2Body));
                            a2.setText(part2Text);
                            a2.setContinuationToPage(detectContinuation(part2Text));
                            a2.setColumnIndex(c);
                            articles.add(a2);
                        }
                        continue;
                    }
                }

                int splitIdx = ENABLE_INTERNAL_HEADLINE_SPLIT
                        ? findInternalHeadlineSplitIndex(ordered, bodyMedian, W)
                        : -1;
                if (splitIdx > 0) {
                    List<Line> part1 = ordered.subList(0, splitIdx);
                    Line splitTitle = ordered.get(splitIdx);
                    List<Line> part2 = ordered.subList(splitIdx + 1, ordered.size());

                    String part1Text = part1.stream().map(Line::getText).collect(Collectors.joining("\n")).trim();
                    String part2Text = part2.stream().map(Line::getText).collect(Collectors.joining("\n")).trim();

                    if (part1Text.length() >= 120 && part2Text.length() >= 120) {
                        if (!isLikelyAd(part1Text)) {
                            PdfArticle a1 = new PdfArticle();
                            a1.setTitle(titleText);
                            a1.setTitleBbox(t.getBbox());
                            a1.setBodyBbox(unionBbox(part1));
                            a1.setText(part1Text);
                            a1.setContinuationToPage(detectContinuation(part1Text));
                            a1.setColumnIndex(c);
                            articles.add(a1);
                        }

                        String secondTitle = PAGE_NUM_STRIP.matcher(splitTitle.getText()).replaceAll("").trim();
                        if (!isLikelyAd(part2Text)) {
                            PdfArticle a2 = new PdfArticle();
                            a2.setTitle(secondTitle);
                            a2.setTitleBbox(splitTitle.getBbox());
                            a2.setBodyBbox(unionBbox(part2));
                            a2.setText(part2Text);
                            a2.setContinuationToPage(detectContinuation(part2Text));
                            a2.setColumnIndex(c);
                            articles.add(a2);
                        }
                        continue;
                    }
                }

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

        articles = enforceUniqueBodyLineOwnership(articles, lines, W, splits, bodyMedian);

        // 다단 지면 읽기 순서: 왼쪽 컬럼 -> 오른쪽 컬럼, 각 컬럼 내 위에서 아래
        articles.sort(Comparator.comparingInt(PdfArticle::getColumnIndex)
                .thenComparingDouble(a -> a.getTitleBbox()[1])
                .thenComparingDouble(a -> a.getTitleBbox()[0]));

        // 페이지 라인에서 기자 줄을 보정하여 기사 끝 신호 강화
        attachReporterLinesFromPage(articles, lines, splits);
        // 최종 reporter/email 메타데이터 채움
        for (PdfArticle article : articles) {
            fillReporterAndEmail(article);
        }

        articles = removeDuplicateArticles(articles);

        return articles;
    }

    private boolean isArticleExpandedToRight(PdfArticle article, List<Double> pageSplits, double pageWidth) {
        if (article == null || article.getBodyBbox() == null) {
            return false;
        }
        int col = article.getColumnIndex();
        if (col < 0 || col >= pageSplits.size()) {
            return false;
        }
        double boundaryX = pageSplits.get(col);
        double expansionThreshold = pageWidth * 0.02;
        return article.getBodyBbox()[2] >= boundaryX + expansionThreshold;
    }

    private boolean isAllowedColumnForArticleLine(
            PdfArticle article,
            int lineCol,
            List<Double> pageSplits,
            double pageWidth
    ) {
        int articleCol = article.getColumnIndex();
        if (lineCol == articleCol) {
            return true;
        }

        // 기본값: 다른 컬럼 라인은 제외
        // 예외: 왼쪽 제목 기사의 본문 bbox가 오른쪽 인접 컬럼까지 확장된 경우만 허용
        if (lineCol != articleCol + 1) {
            return false;
        }
        if (articleCol < 0 || articleCol >= pageSplits.size()) {
            return false;
        }
        double[] bb = article.getBodyBbox();
        if (bb == null || bb.length < 4) {
            return false;
        }
        double boundaryX = pageSplits.get(articleCol);
        double expansionThreshold = pageWidth * 0.02;
        return bb[2] >= boundaryX + expansionThreshold;
    }

    /**
     * 같은 컬럼에서 매우 근접하게 분리된 기사 조각을, 첫 조각에 기자 줄이 없으면 이어 붙입니다.
     * 목적: 소제목/강조문이 제목으로 오탐되어 기사가 잘리는 현상 완화
     */
    private List<PdfArticle> mergeFragmentsUntilReporter(List<PdfArticle> sortedArticles) {
        if (sortedArticles.isEmpty()) {
            return sortedArticles;
        }

        List<PdfArticle> merged = new ArrayList<>();
        for (PdfArticle current : sortedArticles) {
            if (merged.isEmpty()) {
                merged.add(current);
                continue;
            }

            PdfArticle prev = merged.get(merged.size() - 1);
            boolean sameColumn = prev.getColumnIndex() == current.getColumnIndex();
            boolean prevHasReporter = containsReporterEmail(prev.getText());
            double gap = current.getTitleBbox()[1] - prev.getBodyBbox()[3];
            boolean closeEnough = gap >= -6 && gap <= 36;

            if (sameColumn && !prevHasReporter && closeEnough) {
                String extra = (current.getTitle() + "\n" + current.getText()).trim();
                String base = prev.getText() == null ? "" : prev.getText().trim();
                prev.setText((base + "\n" + extra).trim());

                double[] pb = prev.getBodyBbox();
                double[] cb = current.getBodyBbox();
                prev.setBodyBbox(new double[] {
                        Math.min(pb[0], cb[0]),
                        Math.min(pb[1], cb[1]),
                        Math.max(pb[2], cb[2]),
                        Math.max(pb[3], cb[3])
                });

                if (prev.getContinuationToPage() == null && current.getContinuationToPage() != null) {
                    prev.setContinuationToPage(current.getContinuationToPage());
                }
            } else {
                merged.add(current);
            }
        }
        return merged;
    }

    /**
     * 페이지 전체에서 기자명+이메일 줄을 찾아 가장 가까운 이전 기사에 붙입니다.
     * 목적: bbox 경계 때문에 기사 본문에서 빠진 기자 줄 보정
     */
    private void attachReporterLinesFromPage(List<PdfArticle> articles, List<Line> lines, List<Double> splits) {
        if (articles.isEmpty() || lines.isEmpty()) {
            return;
        }

        for (Line line : lines) {
            String t = line.getText();
            if (t == null || t.isBlank()) {
                continue;
            }
            if (!REPORTER_EMAIL_PATTERN.matcher(t).find()) {
                continue;
            }

            int lineCol = assignCol(line.getX0(), splits);
            double lineY = line.getY0();
            PdfArticle best = null;
            double bestDistance = Double.MAX_VALUE;

            for (PdfArticle article : articles) {
                if (article.getColumnIndex() != lineCol) {
                    continue;
                }
                if (article.getTitleBbox()[1] > lineY) {
                    continue;
                }

                double distance = Math.abs(lineY - article.getBodyBbox()[3]);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = article;
                }
            }

            if (best == null || bestDistance > 140) {
                continue;
            }

            String body = best.getText() == null ? "" : best.getText();
            if (!body.contains(t)) {
                best.setText((body + "\n" + t).trim());
                double[] bb = best.getBodyBbox();
                double[] lb = line.getBbox();
                best.setBodyBbox(new double[] {
                        Math.min(bb[0], lb[0]),
                        Math.min(bb[1], lb[1]),
                        Math.max(bb[2], lb[2]),
                        Math.max(bb[3], lb[3])
                });
            }
        }
    }

    private boolean containsReporterEmail(String text) {
        return text != null && REPORTER_EMAIL_PATTERN.matcher(text).find();
    }

    private void fillReporterAndEmail(PdfArticle article) {
        if (article == null || article.getText() == null) {
            return;
        }

        Matcher matcher = REPORTER_EMAIL_PATTERN.matcher(article.getText());
        String lastName = null;
        String lastEmail = null;
        while (matcher.find()) {
            lastName = matcher.group(1);
            lastEmail = matcher.group(2);
        }

        if (lastName != null && lastEmail != null) {
            article.setReporter(lastName);
            article.setEmail(lastEmail);
        }
    }

    // ──────────────────────── internal process ────────────────────────

    private List<PdfArticle> removeDuplicateArticles(List<PdfArticle> articles) {
        if (articles == null || articles.size() < 2) {
            return articles;
        }

        List<PdfArticle> kept = new ArrayList<>();
        for (PdfArticle candidate : articles) {
            PdfArticle duplicate = null;
            for (PdfArticle existing : kept) {
                boolean sameTitle = normalizeForCompare(candidate.getTitle())
                        .equals(normalizeForCompare(existing.getTitle()));
                boolean overlapHigh = bboxOverlapRatio(candidate.getBodyBbox(), existing.getBodyBbox()) >= 0.50;
                boolean containHigh = isHighContainment(
                        normalizeForCompare(candidate.getText()),
                        normalizeForCompare(existing.getText()));
                boolean sameColumn = candidate.getColumnIndex() == existing.getColumnIndex();
                double titleYDistance = Math.abs(candidate.getTitleBbox()[1] - existing.getTitleBbox()[1]);
                boolean sameTitleNear = sameTitle && (sameColumn || titleYDistance <= 450.0);

                if (sameTitleNear || (sameTitle && overlapHigh) || (sameTitle && containHigh) || (overlapHigh && containHigh)) {
                    duplicate = existing;
                    break;
                }
            }

            if (duplicate == null) {
                kept.add(candidate);
                continue;
            }

            int candLen = normalizeForCompare(candidate.getText()).length();
            int dupLen = normalizeForCompare(duplicate.getText()).length();
            if (candLen > dupLen) {
                kept.remove(duplicate);
                kept.add(candidate);
            }
        }

        kept.sort(Comparator.comparingInt(PdfArticle::getColumnIndex)
                .thenComparingDouble(a -> a.getTitleBbox()[1])
                .thenComparingDouble(a -> a.getTitleBbox()[0]));
        return kept;
    }

    private String normalizeForCompare(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\uac00-\\ud7a3]+", "");
    }

    private boolean isHighContainment(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        String longer = a.length() >= b.length() ? a : b;
        String shorter = a.length() >= b.length() ? b : a;
        if (shorter.length() < 40) {
            return false;
        }
        return longer.contains(shorter) && ((double) shorter.length() / longer.length()) >= 0.40;
    }

    private double bboxOverlapRatio(double[] a, double[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) {
            return 0.0;
        }
        double ix0 = Math.max(a[0], b[0]);
        double iy0 = Math.max(a[1], b[1]);
        double ix1 = Math.min(a[2], b[2]);
        double iy1 = Math.min(a[3], b[3]);
        double interW = Math.max(0.0, ix1 - ix0);
        double interH = Math.max(0.0, iy1 - iy0);
        double interArea = interW * interH;
        if (interArea <= 0.0) {
            return 0.0;
        }
        double areaA = Math.max(1.0, (a[2] - a[0]) * (a[3] - a[1]));
        double areaB = Math.max(1.0, (b[2] - b[0]) * (b[3] - b[1]));
        return interArea / Math.min(areaA, areaB);
    }

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
