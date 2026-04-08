package com.qroad.be.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@Component
public class PdfExtractorEngine {
    private static final Logger log = LoggerFactory.getLogger(PdfExtractorEngine.class);

    private final OcrService ocrService;

    @Autowired
    public PdfExtractorEngine(OcrService ocrService) {
        this.ocrService = ocrService;
    }
    private static final boolean ENABLE_SINGLE_COLUMN_SPLIT_PATCH = true;
    private static final boolean ENABLE_SUBCOLUMN_BODY_FILTER = true;
    private static final boolean ENABLE_INTERNAL_HEADLINE_SPLIT = true;
    private static final boolean ENABLE_REVIEW_CANDIDATE_LOG = true;
    private static final Pattern PAGE_NUM_STRIP = Pattern.compile("^(\\d+)\\s+|\\s+(\\d+)$");

    
    
    public String extractText(byte[] pdfBytes) throws IOException {
        try (InputStream is = new java.io.ByteArrayInputStream(pdfBytes)) {
            ProcessResult result = processPdf(is);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < result.getArticles().size(); i++) {
                PdfArticle a = result.getArticles().get(i);
                sb.append("=".repeat(60)).append("\n");
                sb.append(String.format("[기사 %d | 지면 %d면 | %s]%n", i + 1, a.getPage(), a.getId()));
                sb.append("=".repeat(60)).append("\n");
                sb.append("제목: ").append(a.getTitle().trim()).append("\n\n");
                sb.append(a.getText().trim()).append("\n\n\n");
            }
            return sb.toString();
        }
    }

    
    
    public ExtractionResult extractWithImages(byte[] pdfBytes) throws IOException {
        return extractWithImages(pdfBytes, null);
    }

    
    public ExtractionResult extractWithImages(byte[] pdfBytes, BiConsumer<Integer, Integer> progressCallback) throws IOException {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
            List<PdfArticle> allArticles = new ArrayList<>();
            int totalPages = document.getNumberOfPages();
            Map<Integer, List<ImagePositionExtractor.ImageBoundingBox>> pageImagesMap = new HashMap<>();
            ImagePositionExtractor imageExtractor = new ImagePositionExtractor();

            for (int pi = 0; pi < totalPages; pi++) {
                PDPage page = document.getPage(pi);
                List<ImagePositionExtractor.ImageBoundingBox> pImgs = imageExtractor.extract(page);
                pageImagesMap.put(pi, new ArrayList<>(pImgs));

                List<PdfArticle> pageArticles = buildArticlesForPage(document, pi);
                for (PdfArticle a : pageArticles) {
                    a.setPage(pi + 1);
                    allArticles.add(a);
                }
                if (progressCallback != null) {
                    progressCallback.accept(pi + 1, totalPages);
                }
            }
            allArticles = PdfArticleDeduplicator.removeDuplicateArticles(allArticles);
            assignStableArticleIds(allArticles);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < allArticles.size(); i++) {
                PdfArticle a = allArticles.get(i);
                sb.append("=".repeat(60)).append("\n");
                sb.append(String.format("[기사 %d | 지면 %d면 | %s]%n", i + 1, a.getPage(), a.getId()));
                sb.append("=".repeat(60)).append("\n");
                sb.append("제목: ").append(a.getTitle().trim()).append("\n\n");
                sb.append(a.getText().trim()).append("\n\n\n");
            }
            List<ArticleImageData> images = new ArrayList<>();
            /*
            for (PdfArticle a : allArticles) {
            }
            */

            return new ExtractionResult(sb.toString(), images, allArticles);
        }
    }

    
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
    
    
    
        private List<PdfArticle> buildArticlesForPage(PDDocument document, int pageIndex) throws IOException {
        PDPage page = document.getPage(pageIndex);
        PDRectangle mediaBox = page.getMediaBox();
        double pageWidth = mediaBox.getWidth();
        double pageHeight = mediaBox.getHeight();
        PdfTextExtractor.ExtractResult result = PdfTextExtractor.extractLines(page, document, pageIndex);
        List<Line> lines = result.getLines();
        List<Double> sizes = result.getSizes();
        if (lines.isEmpty() || sizes.isEmpty()) {
            return Collections.emptyList();
        }
        if (PdfAdFilter.isLikelyClassifiedPage(lines)) {
            log.info("분류광고 성격 페이지로 판정되어 기사 추출 제외: page={}", pageIndex + 1);
            return Collections.emptyList();
        }
        List<Double> sortedSizes = new ArrayList<>(sizes);
        Collections.sort(sortedSizes);
        double bodyMedian = sortedSizes.get(sortedSizes.size() / 2);
        List<Line> titles = lines.stream()
                .filter(line -> PdfTitleSupport.isProbableTitle(line, bodyMedian))
                .collect(Collectors.toList());
        titles = PdfTitleSupport.filterMainTitleCandidates(titles, lines, bodyMedian, pageWidth);
        titles = PdfTitleSupport.augmentMissingUpperHeadlinePerColumn(titles, lines, bodyMedian, pageWidth, pageHeight);
        titles = PdfTitleSupport.mergeStackedTitleCandidates(titles, bodyMedian, pageWidth);
        titles = suppressSubtitleOnlyTitleCandidates(titles, bodyMedian, pageWidth);
        titles = dropBodyLikeOverlongTitleCandidates(titles, bodyMedian, pageWidth);
        if (titles.isEmpty()) {
            return Collections.emptyList();
        }
        List<Double> xsTitle = titles.stream().map(Line::getX0).collect(Collectors.toList());
        List<Double> xsAll = lines.stream().map(Line::getX0).collect(Collectors.toList());
        List<Double> xsForSplits = xsTitle.size() >= 8 ? xsTitle : xsAll;
        List<Double> splits = PdfColumnSupport.inferColumnSplits(xsForSplits, pageWidth);
        if (ENABLE_SINGLE_COLUMN_SPLIT_PATCH) {
            splits = PdfColumnSupport.patchSplitsIfSingleColumnMisdetected(titles, splits, pageWidth);
        }
        Map<Integer, List<Line>> titlesByCol = new TreeMap<>();
        for (Line title : titles) {
            int col = PdfColumnSupport.assignCol(title.getX0(), splits);
            titlesByCol.computeIfAbsent(col, k -> new ArrayList<>()).add(title);
        }
        for (List<Line> colTitles : titlesByCol.values()) {
            colTitles.sort(Comparator.comparingDouble(Line::getY0).thenComparingDouble(Line::getX0));
        }
        double margin = pageWidth * 0.05;
        List<PdfArticle> articles = new ArrayList<>();
        List<String> reviewCandidates = new ArrayList<>();
        for (Map.Entry<Integer, List<Line>> entry : titlesByCol.entrySet()) {
            int column = entry.getKey();
            List<Line> titleList = collapseSubtitleTitles(entry.getValue(), bodyMedian, pageWidth);
            double columnX0 = (column == 0) ? 0 : splits.get(column - 1);
            double columnX1 = (column == splits.size()) ? pageWidth : splits.get(column);
            columnX0 = Math.max(0, columnX0 - margin);
            columnX1 = Math.min(pageWidth, columnX1 + margin);
            for (int i = 0; i < titleList.size(); i++) {
                Line title = titleList.get(i);
                double y0 = title.getBbox()[3];
                double y1 = (i + 1 < titleList.size()) ? titleList.get(i + 1).getBbox()[1] - 2 : pageHeight;
                if (y1 <= y0 + 22) {
                    continue;
                }
                double titleWidth = title.getBbox()[2] - title.getBbox()[0];
                double articleX0;
                double articleX1;
                boolean expandedToRight = false;
                if (titleWidth > pageWidth * 0.4) {
                    articleX0 = Math.max(0, title.getBbox()[0] - margin);
                    articleX1 = Math.min(pageWidth, title.getBbox()[2] + margin);
                } else {
                    articleX0 = columnX0;
                    articleX1 = columnX1;
                }
                if (PdfTitleSupport.shouldExpandToRightAdjacentColumn(title, column, splits, lines, titlesByCol, bodyMedian)) {
                    int rightCol = column + 1;
                    double rightColX1 = (rightCol == splits.size()) ? pageWidth : splits.get(rightCol);
                    articleX1 = Math.max(articleX1, Math.min(pageWidth, rightColX1 + margin));
                    expandedToRight = true;
                    double nextInCurrent = PdfTitleSupport.findNextTitleTopInColumn(
                            titlesByCol.getOrDefault(column, Collections.emptyList()), title.getY0());
                    double nextInRight = PdfTitleSupport.findNextTitleTopInColumn(
                            titlesByCol.getOrDefault(rightCol, Collections.emptyList()), title.getY0());
                    double nextTop = Math.min(nextInCurrent, nextInRight);
                    if (nextTop != Double.MAX_VALUE) {
                        y1 = Math.min(y1, nextTop - 2);
                    }
                }
                double[] bodyBbox = PdfColumnSupport.clampBbox(new double[]{articleX0, y0, articleX1, y1}, mediaBox);
                List<Line> bodyLines = PdfColumnSupport.linesInBbox(lines, bodyBbox);
                if (ENABLE_SUBCOLUMN_BODY_FILTER) {
                    bodyLines = PdfColumnSupport.filterBodyLinesByAnchorSubColumn(
                            bodyLines, articleX0, articleX1, title.getX0(), expandedToRight);
                }
                List<Line> ordered = PdfColumnSupport.sortLinesReadingOrder(bodyLines, pageWidth);
                int reporterIdx = PdfTitleSupport.findReporterBoundaryIndex(ordered);
                int forcedSplitIdx = -1;
                if (reporterIdx >= 0 && reporterIdx < ordered.size() - 6) {
                    forcedSplitIdx = PdfTitleSupport.findFirstStrongTitleInTail(ordered, reporterIdx + 1, bodyMedian, pageWidth);
                    if (forcedSplitIdx >= 0 && forcedSplitIdx < ordered.size()) {
                        Line forcedSplitTitle = ordered.get(forcedSplitIdx);
                        String forcedText = forcedSplitTitle.getText() == null ? "" : forcedSplitTitle.getText().trim();
                        boolean looksValidTitle = PdfTitleSupport.isProbableTitle(forcedSplitTitle, bodyMedian)
                                && forcedText.length() >= 8
                                && forcedText.length() <= 90
                                && !forcedText.contains("@");
                        if (!looksValidTitle) {
                            forcedSplitIdx = -1;
                        }
                    }
                }
                String text = ordered.stream().map(Line::getText).collect(Collectors.joining("\n")).trim();
                if (text.length() < 50) {
                    if (ENABLE_REVIEW_CANDIDATE_LOG) {
                        reviewCandidates.add(String.format("page=%d, reason=본문 길이 부족, title=%s", pageIndex + 1, title.getText()));
                    }
                    continue;
                }
                if (PdfAdFilter.isLikelyAd(text)) {
                    if (ENABLE_REVIEW_CANDIDATE_LOG) {
                        reviewCandidates.add(String.format("page=%d, reason=광고 의심 본문, title=%s", pageIndex + 1, title.getText()));
                    }
                    continue;
                }
                Integer continuationToPage = PdfArticlePostProcessor.detectContinuation(text);
                String titleText = PAGE_NUM_STRIP.matcher(title.getText()).replaceAll("").trim();
                if (forcedSplitIdx > 0) {
                    List<Line> part1 = ordered.subList(0, reporterIdx + 1);
                    Line splitTitle = ordered.get(forcedSplitIdx);
                    List<Line> part2Body = ordered.subList(forcedSplitIdx + 1, ordered.size());
                    String part1Text = part1.stream().map(Line::getText).collect(Collectors.joining("\n")).trim();
                    String part2Text = part2Body.stream().map(Line::getText).collect(Collectors.joining("\n")).trim();
                    if (part1Text.length() >= 70 && part2Text.length() >= 70) {
                        if (!PdfAdFilter.isLikelyAd(part1Text)) {
                            PdfArticle article1 = new PdfArticle();
                            article1.setTitle(titleText);
                            article1.setTitleBbox(title.getBbox());
                            article1.setBodyBbox(PdfColumnSupport.unionBbox(part1));
                            article1.setText(part1Text);
                            article1.setContinuationToPage(PdfArticlePostProcessor.detectContinuation(part1Text));
                            article1.setColumnIndex(column);
                            articles.add(article1);
                        }
                        String secondTitle = PAGE_NUM_STRIP.matcher(splitTitle.getText()).replaceAll("").trim();
                        if (!PdfAdFilter.isLikelyAd(part2Text)) {
                            PdfArticle article2 = new PdfArticle();
                            article2.setTitle(secondTitle);
                            article2.setTitleBbox(splitTitle.getBbox());
                            article2.setBodyBbox(PdfColumnSupport.unionBbox(part2Body));
                            article2.setText(part2Text);
                            article2.setContinuationToPage(PdfArticlePostProcessor.detectContinuation(part2Text));
                            article2.setColumnIndex(column);
                            articles.add(article2);
                        }
                        continue;
                    }
                }
                int splitIdx = ENABLE_INTERNAL_HEADLINE_SPLIT
                        ? PdfTitleSupport.findInternalHeadlineSplitIndex(ordered, bodyMedian, pageWidth)
                        : -1;
                if (splitIdx > 0) {
                    List<Line> part1 = ordered.subList(0, splitIdx);
                    Line splitTitle = ordered.get(splitIdx);
                    List<Line> part2 = ordered.subList(splitIdx + 1, ordered.size());
                    String part1Text = part1.stream().map(Line::getText).collect(Collectors.joining("\n")).trim();
                    String part2Text = part2.stream().map(Line::getText).collect(Collectors.joining("\n")).trim();
                    if (part1Text.length() >= 70 && part2Text.length() >= 70) {
                        if (!PdfAdFilter.isLikelyAd(part1Text)) {
                            PdfArticle article1 = new PdfArticle();
                            article1.setTitle(titleText);
                            article1.setTitleBbox(title.getBbox());
                            article1.setBodyBbox(PdfColumnSupport.unionBbox(part1));
                            article1.setText(part1Text);
                            article1.setContinuationToPage(PdfArticlePostProcessor.detectContinuation(part1Text));
                            article1.setColumnIndex(column);
                            articles.add(article1);
                        }
                        String secondTitle = PAGE_NUM_STRIP.matcher(splitTitle.getText()).replaceAll("").trim();
                        if (!PdfAdFilter.isLikelyAd(part2Text)) {
                            PdfArticle article2 = new PdfArticle();
                            article2.setTitle(secondTitle);
                            article2.setTitleBbox(splitTitle.getBbox());
                            article2.setBodyBbox(PdfColumnSupport.unionBbox(part2));
                            article2.setText(part2Text);
                            article2.setContinuationToPage(PdfArticlePostProcessor.detectContinuation(part2Text));
                            article2.setColumnIndex(column);
                            articles.add(article2);
                        }
                        continue;
                    }
                }
                PdfArticle article = new PdfArticle();
                article.setTitle(titleText);
                article.setTitleBbox(title.getBbox());
                article.setBodyBbox(bodyBbox);
                article.setText(text);
                article.setContinuationToPage(continuationToPage);
                article.setColumnIndex(column);
                articles.add(article);
            }
        }
        articles = PdfArticlePostProcessor.enforceUniqueBodyLineOwnership(
                articles, lines, pageWidth, splits, bodyMedian);
        articles.sort(Comparator.comparingInt(PdfArticle::getColumnIndex)
                .thenComparingDouble(a -> a.getTitleBbox()[1])
                .thenComparingDouble(a -> a.getTitleBbox()[0]));
        List<Double> finalSplits = splits;
        PdfReporterResolver.attachReporterLinesFromPage(
                articles, lines, x -> PdfColumnSupport.assignCol(x, finalSplits));
        PdfReporterResolver.backfillReporterFromColumnWindow(
                articles, lines, x -> PdfColumnSupport.assignCol(x, finalSplits));
        for (PdfArticle article : articles) {
            PdfReporterResolver.fillReporterAndEmail(article);
        }
        articles = promoteUpperHeadlineForSubheadingTitles(articles, titles, bodyMedian, pageWidth);
        articles = supplementMissingQuotedHeadlineArticles(
                articles, titles, lines, splits, mediaBox, pageWidth, pageHeight, bodyMedian);
        articles = PdfArticleDeduplicator.removeDuplicateArticles(articles);
        for (PdfArticle article : articles) {
            PdfReporterResolver.fillReporterAndEmail(article);
        }
        
        return articles;
    }

    private List<PdfArticle> supplementMissingQuotedHeadlineArticles(
            List<PdfArticle> articles,
            List<Line> titleCandidates,
            List<Line> lines,
            List<Double> splits,
            PDRectangle mediaBox,
            double pageWidth,
            double pageHeight,
            double bodyMedian
    ) {
        if (articles == null || titleCandidates == null || titleCandidates.isEmpty()) return articles;

        double margin = pageWidth * 0.05;
        List<Line> quotedTitles = titleCandidates.stream()
                .filter(t -> t != null && t.getText() != null)
                .filter(t -> {
                    String text = t.getText().trim();
                    return text.startsWith("\"") || text.startsWith("“") || text.startsWith("‘");
                })
                .toList();
        log.info("[QUOTE-SUPPLEMENT] quotedTitlesCount={}", quotedTitles.size());

        if (quotedTitles.isEmpty()) return articles;

        Set<String> existingTitleKeys = articles.stream()
                .map(PdfArticle::getTitle)
                .filter(Objects::nonNull)
                .map(this::normTitleKey)
                .collect(Collectors.toSet());

        for (Line qt : quotedTitles) {
            String qtTitle = PAGE_NUM_STRIP.matcher(qt.getText()).replaceAll("").trim();
            String qtKey = normTitleKey(qtTitle);
            if (qtKey.isEmpty()) continue;
            if (existingTitleKeys.stream().anyMatch(k -> k.contains(qtKey) || qtKey.contains(k))) {
                log.debug("[QUOTE-SUPPLEMENT] skip existing title={}", qtTitle);
                continue;
            }

            int col = PdfColumnSupport.assignCol(qt.getX0(), splits);
            double colX0 = (col == 0) ? 0 : splits.get(col - 1);
            double colX1 = (col == splits.size()) ? pageWidth : splits.get(col);
            double articleX0 = Math.max(0, colX0 - margin);
            double articleX1 = Math.min(pageWidth, colX1 + margin);

            double y0 = qt.getBbox()[3];
            double nextTitleTop = titleCandidates.stream()
                    .filter(t -> t.getY0() > qt.getY0())
                    .filter(t -> Math.abs(t.getX0() - qt.getX0()) <= pageWidth * 0.20)
                    .mapToDouble(Line::getY0)
                    .min()
                    .orElseGet(() -> titleCandidates.stream()
                            .filter(t -> PdfColumnSupport.assignCol(t.getX0(), splits) == col)
                            .filter(t -> t.getY0() > qt.getY0())
                            .mapToDouble(Line::getY0)
                            .min()
                            .orElse(pageHeight));
            double y1 = Math.max(y0 + bodyMedian * 5.0, nextTitleTop - 2);
            y1 = Math.min(y1, pageHeight);

            double[] bodyBbox = PdfColumnSupport.clampBbox(new double[]{articleX0, y0, articleX1, y1}, mediaBox);
            List<Line> bodyLines = PdfColumnSupport.linesInBbox(lines, bodyBbox);
            List<Line> strictBodyLines = PdfColumnSupport.filterBodyLinesByAnchorSubColumn(
                    bodyLines, articleX0, articleX1, qt.getX0(), false);
            List<Line> ordered = PdfColumnSupport.sortLinesReadingOrder(strictBodyLines, pageWidth);
            String bodyText = ordered.stream().map(Line::getText).collect(Collectors.joining("\n")).trim();

            if (bodyText.length() < 70) {
                // Quoted headline은 OCR 흔들림으로 서브컬럼 필터가 본문을 과도 제거하는 경우가 있어 완화 재시도.
                double relaxedX0 = Math.max(0, qt.getBbox()[0] - pageWidth * 0.18);
                double relaxedX1 = Math.min(pageWidth, qt.getBbox()[2] + pageWidth * 0.18);
                double relaxedY1 = Math.min(pageHeight, Math.max(y1, y0 + pageHeight * 0.35));
                double[] relaxedBbox = PdfColumnSupport.clampBbox(new double[]{relaxedX0, y0, relaxedX1, relaxedY1}, mediaBox);
                List<Line> relaxedLines = PdfColumnSupport.linesInBbox(lines, relaxedBbox);
                List<Line> relaxedOrdered = PdfColumnSupport.sortLinesReadingOrder(relaxedLines, pageWidth);
                String relaxedText = relaxedOrdered.stream().map(Line::getText).collect(Collectors.joining("\n")).trim();
                if (relaxedText.length() > bodyText.length()) {
                    ordered = relaxedOrdered;
                    bodyText = relaxedText;
                    bodyBbox = relaxedBbox;
                }
            }
            if (bodyText.length() < 70) {
                log.info("[QUOTE-SUPPLEMENT] skip short body title={} len={}", qtTitle, bodyText.length());
                continue;
            }
            if (PdfAdFilter.isLikelyAd(bodyText)) {
                String lower = bodyText.toLowerCase(Locale.ROOT);
                boolean hasPhone = bodyText.matches("(?s).*\\b\\d{2,3}[-.]\\d{3,4}[-.]\\d{4}\\b.*");
                boolean hasUrl = lower.contains("http://") || lower.contains("https://") || lower.contains("www.");
                boolean hasExplicitAdMarker = bodyText.contains("[광고]")
                        || bodyText.contains("(광고)")
                        || bodyText.contains("광고문의")
                        || bodyText.contains("홍보문의");
                if (hasPhone || hasUrl || hasExplicitAdMarker) {
                    log.info("[QUOTE-SUPPLEMENT] skip ad body title={}", qtTitle);
                    continue;
                }
                log.info("[QUOTE-SUPPLEMENT] ad-like but accepted for quoted title={}", qtTitle);
            }

            PdfArticle fallback = new PdfArticle();
            fallback.setTitle(qtTitle);
            fallback.setTitleBbox(qt.getBbox());
            fallback.setBodyBbox(PdfColumnSupport.unionBbox(ordered));
            fallback.setText(bodyText);
            fallback.setContinuationToPage(PdfArticlePostProcessor.detectContinuation(bodyText));
            fallback.setColumnIndex(col);
            articles.add(fallback);
            existingTitleKeys.add(qtKey);
            log.info("[QUOTE-SUPPLEMENT] added title={} bodyLen={}", qtTitle, bodyText.length());
        }

        articles.sort(Comparator.comparingInt(PdfArticle::getColumnIndex)
                .thenComparingDouble(a -> a.getTitleBbox() == null ? Double.MAX_VALUE : a.getTitleBbox()[1])
                .thenComparingDouble(a -> a.getTitleBbox() == null ? Double.MAX_VALUE : a.getTitleBbox()[0]));
        return articles;
    }

    private String normTitleKey(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\uac00-\\ud7a3]+", "");
    }

    private List<PdfArticle> promoteUpperHeadlineForSubheadingTitles(
            List<PdfArticle> articles,
            List<Line> titleCandidates,
            double bodyMedian,
            double pageWidth
    ) {
        if (articles == null || articles.isEmpty() || titleCandidates == null || titleCandidates.isEmpty()) {
            return articles;
        }

        Map<String, Line> titleLineMap = new HashMap<>();
        for (Line line : titleCandidates) {
            String key = normTitleKey(line.getText());
            if (!key.isEmpty() && !titleLineMap.containsKey(key)) {
                titleLineMap.put(key, line);
            }
        }

        Set<String> existingTitleKeys = articles.stream()
                .map(PdfArticle::getTitle)
                .filter(Objects::nonNull)
                .map(this::normTitleKey)
                .collect(Collectors.toSet());

        for (PdfArticle article : articles) {
            String title = article.getTitle();
            if (title == null || title.isBlank()) continue;

            String key = normTitleKey(title);
            Line subheading = titleLineMap.get(key);
            if (subheading == null) continue;

            Line upperHeadline = titleCandidates.stream()
                    .filter(l -> l != subheading)
                    .filter(l -> l.getY0() < subheading.getY0())
                    .filter(l -> Math.abs(l.getX0() - subheading.getX0()) <= pageWidth * 0.06)
                    .filter(l -> {
                        double gap = subheading.getY0() - l.getY1();
                        return gap >= -bodyMedian * 0.2 && gap <= bodyMedian * 6.0;
                    })
                    .filter(l -> l.getMaxSize() >= bodyMedian * 1.8)
                    .filter(l -> l.getWidth() >= subheading.getWidth() * 1.4)
                    .max(Comparator.comparingDouble(Line::getMaxSize))
                    .orElse(null);

            if (upperHeadline == null) continue;

            String upperKey = normTitleKey(upperHeadline.getText());
            if (upperKey.isEmpty()) continue;
            if (existingTitleKeys.contains(upperKey)) continue;

            String mergedTitle = ((upperHeadline.getText() == null ? "" : upperHeadline.getText().trim())
                    + "\n" + title.trim()).trim();
            article.setTitle(mergedTitle);
            existingTitleKeys.add(normTitleKey(mergedTitle));
        }
        return articles;
    }

    private List<Line> collapseSubtitleTitles(List<Line> sortedTitles, double bodyMedian, double pageWidth) {
        if (sortedTitles == null || sortedTitles.size() < 2) {
            return sortedTitles == null ? Collections.emptyList() : sortedTitles;
        }
        List<Line> collapsed = new ArrayList<>();
        int i = 0;
        while (i < sortedTitles.size()) {
            Line current = sortedTitles.get(i);
            if (i + 1 < sortedTitles.size()) {
                Line next = sortedTitles.get(i + 1);
                if (isLikelySubtitlePair(current, next, bodyMedian, pageWidth)) {
                    String mergedText = ((current.getText() == null ? "" : current.getText().trim()) + "\n"
                            + (next.getText() == null ? "" : next.getText().trim())).trim();
                    double[] cb = current.getBbox();
                    double[] nb = next.getBbox();
                    double[] mergedBbox = new double[] {
                            Math.min(cb[0], nb[0]),
                            Math.min(cb[1], nb[1]),
                            Math.max(cb[2], nb[2]),
                            Math.max(cb[3], nb[3])
                    };
                    collapsed.add(new Line(
                            mergedText,
                            mergedBbox,
                            Math.max(current.getMaxSize(), next.getMaxSize()),
                            current.isBold() || next.isBold()
                    ));
                    i += 2;
                    continue;
                }
            }
            collapsed.add(current);
            i++;
        }
        return collapsed;
    }

    private boolean isLikelySubtitlePair(Line title, Line subtitle, double bodyMedian, double pageWidth) {
        if (title == null || subtitle == null) return false;
        String t1 = title.getText() == null ? "" : title.getText().trim();
        String t2 = subtitle.getText() == null ? "" : subtitle.getText().trim();
        if (t1.isEmpty() || t2.isEmpty()) return false;

        double gap = subtitle.getY0() - title.getY1();
        boolean closeVertically = gap >= -bodyMedian * 0.4 && gap <= bodyMedian * 2.8;
        boolean closeInX = Math.abs(subtitle.getX0() - title.getX0()) <= pageWidth * 0.06;
        boolean headlineStrong = title.getMaxSize() >= bodyMedian * 1.45;
        boolean subtitleSized = subtitle.getMaxSize() >= bodyMedian * 1.05
                && subtitle.getMaxSize() <= title.getMaxSize() * 0.86;
        boolean subtitleNarrower = subtitle.getWidth() <= title.getWidth() * 1.02;

        return closeVertically && closeInX && headlineStrong && subtitleSized && subtitleNarrower;
    }

    /**
     * Remove subtitle-like candidates that sit directly below a stronger headline.
     * This prevents one article from being split into two articles (headline + subtitle).
     */
    private List<Line> suppressSubtitleOnlyTitleCandidates(List<Line> titles, double bodyMedian, double pageWidth) {
        if (titles == null || titles.size() < 2) {
            return titles == null ? Collections.emptyList() : titles;
        }

        List<Line> sorted = new ArrayList<>(titles);
        sorted.sort(Comparator.comparingDouble(Line::getY0).thenComparingDouble(Line::getX0));
        boolean[] drop = new boolean[sorted.size()];

        for (int i = 1; i < sorted.size(); i++) {
            Line cur = sorted.get(i);
            if (cur == null || cur.getText() == null) continue;
            String curText = cur.getText().trim();
            if (curText.isEmpty()) continue;

            Line bestPrev = null;
            int bestPrevIdx = -1;
            for (int j = i - 1; j >= 0; j--) {
                Line prev = sorted.get(j);
                if (prev == null) continue;
                double overlap = PdfColumnSupport.horizontalOverlapRatio(prev, cur);
                if (overlap < 0.45) continue;
                double gap = cur.getY0() - prev.getY1();
                if (gap < -bodyMedian * 0.4 || gap > bodyMedian * 3.0) continue;
                bestPrev = prev;
                bestPrevIdx = j;
                break;
            }
            if (bestPrev == null) continue;

            double gap = cur.getY0() - bestPrev.getY1();
            boolean prevLooksHeadline = bestPrev.getMaxSize() >= bodyMedian * 1.35
                    && bestPrev.getWidth() >= pageWidth * 0.20;
            boolean curLooksSubtitle = cur.getMaxSize() <= bestPrev.getMaxSize() * 0.88
                    && cur.getMaxSize() >= bodyMedian * 1.02
                    && cur.getWidth() <= bestPrev.getWidth() * 1.03;
            boolean closeX = Math.abs(cur.getX0() - bestPrev.getX0()) <= pageWidth * 0.07;
            boolean closeY = gap >= -bodyMedian * 0.4 && gap <= bodyMedian * 2.8;

            if (prevLooksHeadline && curLooksSubtitle && closeX && closeY) {
                drop[i] = true;
                log.debug("[TITLE-SUB] suppress subtitle candidate: prev='{}' / cur='{}'",
                        bestPrev.getText(), curText);
                // Keep stronger headline, suppress only current subtitle-like candidate.
                if (bestPrevIdx >= 0) {
                    drop[bestPrevIdx] = drop[bestPrevIdx] && bestPrev.getMaxSize() < cur.getMaxSize();
                }
            }
        }

        List<Line> out = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            if (!drop[i]) out.add(sorted.get(i));
        }
        return out;
    }

    /**
     * Remove overlong/body-like title candidates that are often created by OCR row merges.
     */
    private List<Line> dropBodyLikeOverlongTitleCandidates(List<Line> titles, double bodyMedian, double pageWidth) {
        if (titles == null || titles.isEmpty()) {
            return titles == null ? Collections.emptyList() : titles;
        }

        List<Line> out = new ArrayList<>();
        for (Line t : titles) {
            if (t == null || t.getText() == null) continue;
            String text = t.getText().trim();
            if (text.isEmpty()) continue;

            int len = text.length();
            boolean tooLong = len > 95;
            boolean bodyLikeLong = len > 50 && t.getMaxSize() < bodyMedian * 1.22;
            boolean fullRowBodyLike = len > 35
                    && t.getWidth() >= pageWidth * 0.85
                    && t.getMaxSize() < bodyMedian * 1.35;

            if (tooLong || bodyLikeLong || fullRowBodyLike) {
                log.debug("[TITLE-FILTER] drop body-like title candidate: {}", text);
                continue;
            }
            out.add(t);
        }
        return out;
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
    
    private ProcessResult processPdf(InputStream pdfStream) throws IOException {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfStream.readAllBytes())) {
            List<PdfArticle> allArticles = new ArrayList<>();

            for (int pi = 0; pi < document.getNumberOfPages(); pi++) {
                List<PdfArticle> pageArticles = buildArticlesForPage(document, pi);
                for (PdfArticle a : pageArticles) {
                    a.setPage(pi + 1);
                    allArticles.add(a);
                }
            }
            allArticles = PdfArticleDeduplicator.removeDuplicateArticles(allArticles);
            assignStableArticleIds(allArticles);

            return new ProcessResult(allArticles);
        }
    }

    private void assignStableArticleIds(List<PdfArticle> allArticles) {
        if (allArticles == null || allArticles.isEmpty()) return;
        allArticles.sort(Comparator
                .comparingInt(PdfArticle::getPage)
                .thenComparingInt(PdfArticle::getColumnIndex)
                .thenComparingDouble(a -> a.getTitleBbox() == null ? Double.MAX_VALUE : a.getTitleBbox()[1])
                .thenComparingDouble(a -> a.getTitleBbox() == null ? Double.MAX_VALUE : a.getTitleBbox()[0]));

        Map<Integer, Integer> pageCounters = new HashMap<>();
        for (PdfArticle a : allArticles) {
            int page = a.getPage();
            int seq = pageCounters.getOrDefault(page, 0) + 1;
            pageCounters.put(page, seq);
            a.setId(String.format("p%02d_a%03d", page, seq));
        }
    }

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



