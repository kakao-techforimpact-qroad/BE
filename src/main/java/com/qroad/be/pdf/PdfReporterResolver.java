package com.qroad.be.pdf;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class PdfReporterResolver {

    private static final Pattern REPORTER_EMAIL_PATTERN = Pattern.compile(
            "([\\uAC00-\\uD7A3]{2,4})\\s+([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+)"
    );
    private static final Pattern REPORTER_EMAIL_FLEX_PATTERN = Pattern.compile(
            "([\\uAC00-\\uD7A3]{2,4}).{0,6}?([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+)"
    );
    private static final Pattern REPORTER_NAME_ONLY_PATTERN = Pattern.compile(
            "^\\s*(?:/\\s*)?([\\uAC00-\\uD7A3]{2,4})\\s*\\uAE30\\uC790\\s*$"
    );

    private PdfReporterResolver() {
    }

    /**
     * 페이지 전체를 스캔해 기자줄(이름/이메일)을 가장 가까운 기사에 부착합니다.
     */
    static void attachReporterLinesFromPage(
            List<PdfArticle> articles,
            List<Line> lines,
            DoubleToIntFunction assignCol
    ) {
        if (articles.isEmpty() || lines.isEmpty()) {
            return;
        }

        for (Line line : lines) {
            String t = line.getText();
            if (t == null || t.isBlank()) {
                continue;
            }
            if (!isReporterLineCandidate(t)) {
                continue;
            }

            double lineY = line.getY0();
            PdfArticle best = null;
            double bestDistance = Double.MAX_VALUE;

            for (PdfArticle article : articles) {
                if (article.getBodyBbox() == null || article.getTitleBbox() == null) continue;
                if (article.getTitleBbox()[1] > lineY) {
                    continue;
                }

                double[] bb = article.getBodyBbox();
                double distance = Math.abs(lineY - bb[3]);
                boolean sameCol = assignCol.applyAsInt(line.getX0()) == article.getColumnIndex();
                boolean inArticleX = line.getX0() >= bb[0] - 8 && line.getX0() <= bb[2] + 8;
                double colPenalty = sameCol ? 0.0 : (inArticleX ? 45.0 : 120.0);
                double score = distance + colPenalty;
                if (score < bestDistance) {
                    bestDistance = score;
                    best = article;
                }
            }

            if (best == null || bestDistance > 320) {
                continue;
            }

            String extractedReporter = extractReporterNameFromLine(t);
            String extractedEmail = extractReporterEmailFromLine(t);
            if (best.getReporter() == null || best.getReporter().isBlank()) {
                if (extractedReporter != null && !extractedReporter.isBlank()) {
                    best.setReporter(extractedReporter);
                }
            }
            if (best.getEmail() == null || best.getEmail().isBlank()) {
                if (extractedEmail != null && !extractedEmail.isBlank()) {
                    best.setEmail(extractedEmail);
                }
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

    static boolean containsReporterEmail(String text) {
        return text != null
                && (REPORTER_EMAIL_PATTERN.matcher(text).find() || REPORTER_EMAIL_FLEX_PATTERN.matcher(text).find());
    }

    /**
     * 본문 텍스트 내부 패턴으로 기자명/이메일을 최종 확정합니다.
     */
    static void fillReporterAndEmail(PdfArticle article) {
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
        if (lastName == null || lastEmail == null) {
            Matcher flex = REPORTER_EMAIL_FLEX_PATTERN.matcher(article.getText());
            while (flex.find()) {
                lastName = flex.group(1);
                lastEmail = flex.group(2);
            }
        }

        if (lastName != null && lastEmail != null) {
            article.setReporter(lastName);
            article.setEmail(lastEmail);
            return;
        }

        Matcher nameOnly = REPORTER_NAME_ONLY_PATTERN.matcher(article.getText());
        String fallbackReporter = null;
        while (nameOnly.find()) {
            fallbackReporter = nameOnly.group(1);
        }
        if (fallbackReporter != null) {
            article.setReporter(fallbackReporter);
        }
    }

    /**
     * 동일 컬럼 내 세로 구간(window) 기준으로 기자 정보를 보강합니다.
     * bbox 경계로 누락된 기자줄을 복구하기 위한 2차 보정입니다.
     */
    static void backfillReporterFromColumnWindow(
            List<PdfArticle> articles,
            List<Line> lines,
            DoubleToIntFunction assignCol
    ) {
        if (articles == null || articles.isEmpty() || lines == null || lines.isEmpty()) {
            return;
        }

        double pageBottom = lines.stream().mapToDouble(Line::getY1).max().orElse(10_000.0);
        Map<Integer, List<PdfArticle>> byCol = articles.stream()
                .collect(Collectors.groupingBy(PdfArticle::getColumnIndex));

        for (Map.Entry<Integer, List<PdfArticle>> entry : byCol.entrySet()) {
            int col = entry.getKey();
            List<PdfArticle> colArticles = entry.getValue();
            colArticles.sort(Comparator.comparingDouble(a -> a.getTitleBbox()[1]));

            for (int i = 0; i < colArticles.size(); i++) {
                PdfArticle article = colArticles.get(i);
                if (article.getReporter() != null && !article.getReporter().isBlank()) {
                    continue;
                }

                double yMin = article.getTitleBbox()[1];
                double yMax = (i + 1 < colArticles.size())
                        ? colArticles.get(i + 1).getTitleBbox()[1] - 2.0
                        : pageBottom + 2.0;
                if (yMax <= yMin) {
                    continue;
                }

                Line best = null;
                for (Line line : lines) {
                    String text = line.getText();
                    if (text == null || text.isBlank()) continue;
                    if (!isReporterLineCandidate(text)) continue;
                    if (assignCol.applyAsInt(line.getX0()) != col) continue;

                    double ly = line.getY0();
                    if (ly < yMin - 1.0 || ly > yMax + 1.0) continue;
                    if (best == null || ly > best.getY0()) {
                        best = line;
                    }
                }

                if (best == null) {
                    continue;
                }

                String foundReporter = extractReporterNameFromLine(best.getText());
                String foundEmail = extractReporterEmailFromLine(best.getText());
                if (foundReporter != null && !foundReporter.isBlank()) {
                    article.setReporter(foundReporter);
                }
                if (foundEmail != null && !foundEmail.isBlank()) {
                    article.setEmail(foundEmail);
                }

                String body = article.getText() == null ? "" : article.getText();
                if (!body.contains(best.getText())) {
                    article.setText((body + "\n" + best.getText()).trim());
                }
            }
        }
    }

    private static boolean isReporterLineCandidate(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (REPORTER_EMAIL_PATTERN.matcher(trimmed).find() || REPORTER_EMAIL_FLEX_PATTERN.matcher(trimmed).find()) {
            return true;
        }
        return trimmed.length() <= 20 && REPORTER_NAME_ONLY_PATTERN.matcher(trimmed).matches();
    }

    private static String extractReporterNameFromLine(String text) {
        if (text == null) return "";
        Matcher strict = REPORTER_EMAIL_PATTERN.matcher(text);
        if (strict.find()) return strict.group(1);
        Matcher flex = REPORTER_EMAIL_FLEX_PATTERN.matcher(text);
        if (flex.find()) return flex.group(1);
        Matcher nameOnly = REPORTER_NAME_ONLY_PATTERN.matcher(text.trim());
        if (nameOnly.matches()) return nameOnly.group(1);
        return "";
    }

    private static String extractReporterEmailFromLine(String text) {
        if (text == null) return "";
        Matcher strict = REPORTER_EMAIL_PATTERN.matcher(text);
        if (strict.find()) return strict.group(2);
        Matcher flex = REPORTER_EMAIL_FLEX_PATTERN.matcher(text);
        if (flex.find()) return flex.group(2);
        return "";
    }
}
