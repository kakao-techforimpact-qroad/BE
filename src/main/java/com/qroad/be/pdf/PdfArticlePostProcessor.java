package com.qroad.be.pdf;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class PdfArticlePostProcessor {

    private PdfArticlePostProcessor() {
    }

    private static final Pattern CONTINUATION_PATTERN = Pattern.compile("기사\\s*(\\d+)\\s*면\\s*이어짐");

    /**
     * 각 본문 라인이 단 하나의 기사에만 귀속되도록 재할당합니다.
     * 컬럼/거리 점수를 사용해 소유권을 정하고, 기사 본문 텍스트를 다시 구성합니다.
     */
    static List<PdfArticle> enforceUniqueBodyLineOwnership(
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
        for (PdfArticle article : articles) owned.put(article, new ArrayList<>());

        for (Line line : allLines) {
            String text = line.getText();
            if (text == null || text.trim().isEmpty()) continue;
            if (PdfTitleSupport.isProbableTitle(line, bodyMedian)) continue;

            int lineCol = PdfColumnSupport.assignCol(line.getX0(), pageSplits);
            List<PdfArticle> candidates = new ArrayList<>();
            for (PdfArticle article : articles) {
                if (article.getBodyBbox() == null || article.getTitleBbox() == null) continue;
                if (!isAllowedColumnForArticleLine(article, lineCol, pageSplits, pageWidth)) continue;
                if (!PdfColumnSupport.isInsideBboxCenter(line, article.getBodyBbox())) continue;
                if (PdfColumnSupport.overlapsArticleTitleArea(line, article)) continue;
                if (line.getY0() < article.getTitleBbox()[3] - 2) continue;
                candidates.add(article);
            }
            if (candidates.isEmpty()) continue;

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
            if (best != null) owned.get(best).add(line);
        }

        for (PdfArticle article : articles) {
            List<Line> lines = owned.getOrDefault(article, List.of());
            if (lines.size() < 3) continue;
            double[] articleBodyBbox = article.getBodyBbox();
            double[] articleTitleBbox = article.getTitleBbox();
            if (articleBodyBbox != null && articleTitleBbox != null) {
                boolean allowRightAdjacent = isArticleExpandedToRight(article, pageSplits, pageWidth);
                lines = PdfColumnSupport.filterBodyLinesByAnchorSubColumn(
                        lines,
                        articleBodyBbox[0],
                        articleBodyBbox[2],
                        articleTitleBbox[0],
                        allowRightAdjacent
                );
            }
            List<Line> ordered = PdfColumnSupport.sortLinesReadingOrder(lines, pageWidth);
            String rebuilt = ordered.stream().map(Line::getText).collect(Collectors.joining("\n")).trim();
            if (rebuilt.length() < 50) continue;
            article.setText(rebuilt);
            article.setBodyBbox(PdfColumnSupport.unionBbox(ordered));
        }
        return articles;
    }

    /**
     * "기사 N면 이어짐" 패턴을 탐지해 이어짐 대상 페이지를 반환합니다.
     */
    static Integer detectContinuation(String text) {
        Matcher m = CONTINUATION_PATTERN.matcher(text);
        if (m.find()) return Integer.parseInt(m.group(1));
        return null;
    }

    /**
     * 같은 컬럼에서 기자줄이 없는 조각 기사를 다음 조각과 병합합니다.
     * 기자줄이 나타날 때까지 누적해 단편 분리 오류를 완화합니다.
     */
    static List<PdfArticle> mergeFragmentsUntilReporter(List<PdfArticle> sortedArticles) {
        if (sortedArticles == null || sortedArticles.size() <= 1) return sortedArticles;
        List<PdfArticle> merged = new ArrayList<>();
        int i = 0;
        while (i < sortedArticles.size()) {
            PdfArticle current = sortedArticles.get(i);
            if (current.getReporter() != null && !current.getReporter().isBlank()) {
                merged.add(current);
                i++;
                continue;
            }
            StringBuilder text = new StringBuilder(current.getText() == null ? "" : current.getText());
            double[] body = current.getBodyBbox() == null ? null : Arrays.copyOf(current.getBodyBbox(), 4);
            String reporter = current.getReporter();
            String email = current.getEmail();
            int j = i + 1;
            while (j < sortedArticles.size()) {
                PdfArticle next = sortedArticles.get(j);
                String nextText = next.getText() == null ? "" : next.getText();
                if (!nextText.isBlank()) {
                    if (text.length() > 0) text.append("\n");
                    text.append(nextText);
                }
                if (body != null && next.getBodyBbox() != null) {
                    body[0] = Math.min(body[0], next.getBodyBbox()[0]);
                    body[1] = Math.min(body[1], next.getBodyBbox()[1]);
                    body[2] = Math.max(body[2], next.getBodyBbox()[2]);
                    body[3] = Math.max(body[3], next.getBodyBbox()[3]);
                }
                if (next.getReporter() != null && !next.getReporter().isBlank()) {
                    reporter = next.getReporter();
                    email = next.getEmail();
                    j++;
                    break;
                }
                j++;
            }
            PdfArticle combined = new PdfArticle();
            combined.setTitle(current.getTitle());
            combined.setText(text.toString().trim());
            combined.setTitleBbox(current.getTitleBbox());
            combined.setBodyBbox(body);
            combined.setColumnIndex(current.getColumnIndex());
            combined.setReporter(reporter);
            combined.setEmail(email);
            combined.setContinuationToPage(current.getContinuationToPage());
            merged.add(combined);
            i = j;
        }
        return merged;
    }

    private static boolean isArticleExpandedToRight(PdfArticle article, List<Double> pageSplits, double pageWidth) {
        if (article == null || article.getBodyBbox() == null || pageSplits == null || pageSplits.isEmpty()) return false;
        int col = article.getColumnIndex();
        if (col >= pageSplits.size()) return false;
        double colX1 = pageSplits.get(col);
        double[] body = article.getBodyBbox();
        double colX0 = (col == 0) ? 0.0 : pageSplits.get(col - 1);
        double colWidth = Math.max(1.0, colX1 - colX0);
        return body[2] >= colX1 - colWidth * 0.08;
    }

    private static boolean isAllowedColumnForArticleLine(PdfArticle article, int lineCol, List<Double> pageSplits, double pageWidth) {
        int articleCol = article.getColumnIndex();
        if (lineCol == articleCol) return true;
        if (lineCol == articleCol + 1 && isArticleExpandedToRight(article, pageSplits, pageWidth)) return true;
        return false;
    }
}
