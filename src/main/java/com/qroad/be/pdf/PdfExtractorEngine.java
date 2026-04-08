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
                sb.append(String.format("[湲곗궗 %d | ?좊Ц %d硫?| %s]%n", i + 1, a.getPage(), a.getId()));
                sb.append("=".repeat(60)).append("\n");
                sb.append("?쒕ぉ: ").append(a.getTitle().trim()).append("\n\n");
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
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < allArticles.size(); i++) {
                PdfArticle a = allArticles.get(i);
                sb.append("=".repeat(60)).append("\n");
                sb.append(String.format("[湲곗궗 %d | ?좊Ц %d硫?| %s]%n", i + 1, a.getPage(), a.getId()));
                sb.append("=".repeat(60)).append("\n");
                sb.append("?쒕ぉ: ").append(a.getTitle().trim()).append("\n\n");
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
        List<Double> sortedSizes = new ArrayList<>(sizes);
        Collections.sort(sortedSizes);
        double bodyMedian = sortedSizes.get(sortedSizes.size() / 2);
        List<Line> titles = lines.stream()
                .filter(line -> PdfTitleSupport.isProbableTitle(line, bodyMedian))
                .collect(Collectors.toList());
        titles = PdfTitleSupport.filterMainTitleCandidates(titles, lines, bodyMedian, pageWidth);
        titles = PdfTitleSupport.augmentMissingUpperHeadlinePerColumn(titles, lines, bodyMedian, pageWidth, pageHeight);
        titles = PdfTitleSupport.mergeStackedTitleCandidates(titles, bodyMedian, pageWidth);
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
            List<Line> titleList = entry.getValue();
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
                    if (part1Text.length() >= 120 && part2Text.length() >= 120) {
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
                    if (part1Text.length() >= 120 && part2Text.length() >= 120) {
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
        articles = PdfArticleDeduplicator.removeDuplicateArticles(articles);
        for (PdfArticle article : articles) {
            PdfReporterResolver.fillReporterAndEmail(article);
        }
        
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



