package com.qroad.be.pdf;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class PdfTitleSupport {

    private PdfTitleSupport() {
    }

    private static final List<String> BAD_KEYWORDS = List.of("발행", "면", "호", "www", "http", "기자", "전화", "팩스");
    private static final Pattern PAGE_HEADER_PATTERN = Pattern.compile("^\\d+\\s+\\S{1,3}$|^\\S{1,3}\\s+\\d+$");
    private static final Pattern REPORTER_EMAIL_PATTERN = Pattern.compile("([\\uAC00-\\uD7A3]{2,4})\\s+([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+)");
    private static final Pattern SUBHEADING_MARKER_PATTERN = Pattern.compile("^[■◆▶▷·●\\-].*");
    private static final double TITLE_RATIO_THRESHOLD_NON_BOLD = 1.24;
    private static final double TITLE_RATIO_THRESHOLD_BOLD = 1.12;
    private static final double TITLE_ABS_THRESHOLD_NON_BOLD = 11.0;
    private static final double TITLE_ABS_THRESHOLD_BOLD = 10.3;
    private static final double STRONG_HEADLINE_RATIO = 1.58;
    private static final double STRONG_HEADLINE_MIN_WIDTH_RATIO = 0.34;

    
    static boolean isProbableTitle(Line line, double bodyMedian) {
        String t = line.getText();
        if (t.length() < 3 || t.length() > 80) return false;
        double ratioThreshold = line.isBold() ? TITLE_RATIO_THRESHOLD_BOLD : TITLE_RATIO_THRESHOLD_NON_BOLD;
        double absThreshold = line.isBold() ? TITLE_ABS_THRESHOLD_BOLD : TITLE_ABS_THRESHOLD_NON_BOLD;
        if (line.getMaxSize() < Math.max(bodyMedian * ratioThreshold, absThreshold)) return false;
        if (t.length() < 20) {
            for (String kw : BAD_KEYWORDS) if (t.contains(kw)) return false;
        }
        long digits = t.chars().filter(Character::isDigit).count();
        if ((double) digits / Math.max(t.length(), 1) > 0.35) return false;
        if (PAGE_HEADER_PATTERN.matcher(t).matches()) return false;
        if (t.startsWith("\u25B6") || t.startsWith("\u25B7")) return false;
        if (SUBHEADING_MARKER_PATTERN.matcher(t).matches()) return false;
        if (t.contains("@") || REPORTER_EMAIL_PATTERN.matcher(t).find()) return false;
        return true;
    }

    
    static List<Line> filterMainTitleCandidates(List<Line> titleCandidates, List<Line> allLines, double bodyMedian, double pageWidth) {
        if (titleCandidates.isEmpty()) return titleCandidates;
        List<Line> filtered = titleCandidates.stream()
                .filter(t -> !isLikelyEmbeddedSubheading(t, allLines, bodyMedian, pageWidth))
                .collect(Collectors.toList());
        filtered = restoreStandaloneDroppedTitles(titleCandidates, filtered, allLines, bodyMedian, pageWidth);
        filtered = restoreTopTitlesPerColumn(titleCandidates, filtered, pageWidth);
        return filtered.isEmpty() ? titleCandidates : filtered;
    }

    static int findInternalHeadlineSplitIndex(List<Line> ordered, double bodyMedian, double pageWidth) {
        if (ordered == null || ordered.size() < 10) return -1;
        for (int i = 3; i < ordered.size() - 3; i++) {
            Line line = ordered.get(i);
            String text = line.getText() == null ? "" : line.getText().trim();
            if (text.length() < 8 || text.length() > 70) continue;
            if (SUBHEADING_MARKER_PATTERN.matcher(text).matches()) continue;
            if (REPORTER_EMAIL_PATTERN.matcher(text).find()) continue;
            if (text.endsWith("기자") || text.endsWith("보도자료")) continue;
            boolean strongSize = line.getMaxSize() >= bodyMedian * 1.40 || (line.isBold() && line.getMaxSize() >= bodyMedian * 1.28);
            boolean strongWidth = line.getWidth() >= pageWidth * 0.25;
            if (!strongSize || !strongWidth) continue;
            Line prev = ordered.get(i - 1), next = ordered.get(i + 1);
            double topGap = line.getY0() - prev.getY1();
            double bottomGap = next.getY0() - line.getY1();
            boolean separated = topGap >= bodyMedian * 0.45 && bottomGap >= bodyMedian * 0.45;
            if (!separated) continue;
            int beforeChars = ordered.subList(0, i).stream().map(Line::getText).filter(Objects::nonNull).mapToInt(String::length).sum();
            int afterChars = ordered.subList(i + 1, ordered.size()).stream().map(Line::getText).filter(Objects::nonNull).mapToInt(String::length).sum();
            if (beforeChars < 80 || afterChars < 80) continue;
            return i;
        }
        return -1;
    }

    static int findReporterBoundaryIndex(List<Line> ordered) {
        int last = -1;
        for (int i = 0; i < ordered.size(); i++) {
            String text = ordered.get(i).getText();
            if (text != null && REPORTER_EMAIL_PATTERN.matcher(text).find()) last = i;
        }
        return last;
    }

    static int findFirstStrongTitleInTail(List<Line> lines, int start, double bodyMedian, double pageWidth) {
        for (int i = Math.max(0, start); i < lines.size(); i++) {
            if (isLikelyNewArticleTitleAfterReporter(lines.get(i), bodyMedian, pageWidth)) return i;
        }
        return -1;
    }

    
    static List<Line> augmentMissingUpperHeadlinePerColumn(List<Line> titles, List<Line> allLines, double bodyMedian, double pageWidth, double pageHeight) {
        if (titles == null || titles.isEmpty()) return titles == null ? Collections.emptyList() : titles;
        List<Double> splits = PdfColumnSupport.inferColumnSplits(titles.stream().map(Line::getX0).collect(Collectors.toList()), pageWidth);
        Map<Integer, List<Line>> titlesByCol = new TreeMap<>();
        for (Line t : titles) titlesByCol.computeIfAbsent(PdfColumnSupport.assignCol(t.getX0(), splits), k -> new ArrayList<>()).add(t);
        for (List<Line> colTitles : titlesByCol.values()) colTitles.sort(Comparator.comparingDouble(Line::getY0).thenComparingDouble(Line::getX0));
        Set<Line> augmented = new LinkedHashSet<>(titles);
        for (Map.Entry<Integer, List<Line>> entry : titlesByCol.entrySet()) {
            int col = entry.getKey();
            double topY = entry.getValue().get(0).getY0();
            double colX0 = (col == 0) ? 0 : splits.get(col - 1);
            double colX1 = (col == splits.size()) ? pageWidth : splits.get(col);
            Line bestCandidate = null;
            for (Line line : allLines) {
                String text = line.getText() == null ? "" : line.getText().trim();
                if (text.isEmpty()) continue;
                if (line.getY0() >= topY - bodyMedian * 0.2) continue;
                if (line.getY0() > pageHeight * 0.45) continue;
                double cx = (line.getBbox()[0] + line.getBbox()[2]) / 2.0;
                if (cx < colX0 || cx > colX1) continue;
                if (!isRelaxedUpperHeadlineCandidate(line, bodyMedian, pageWidth)) continue;
                if (bestCandidate == null || line.getY0() < bestCandidate.getY0()) bestCandidate = line;
            }
            if (bestCandidate != null) augmented.add(bestCandidate);
        }
        List<Line> result = new ArrayList<>(augmented);
        result.sort(Comparator.comparingDouble(Line::getY0).thenComparingDouble(Line::getX0));
        return result;
    }

    
    static List<Line> mergeStackedTitleCandidates(List<Line> titles, double bodyMedian, double pageWidth) {
        if (titles == null || titles.size() < 2) return titles == null ? Collections.emptyList() : titles;
        List<Line> sorted = new ArrayList<>(titles);
        sorted.sort(Comparator.comparingDouble(Line::getY0).thenComparingDouble(Line::getX0));
        List<Line> merged = new ArrayList<>();
        boolean[] used = new boolean[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            if (used[i]) continue;
            Line base = sorted.get(i);
            used[i] = true;
            StringBuilder text = new StringBuilder(base.getText() == null ? "" : base.getText().trim());
            double[] bbox = Arrays.copyOf(base.getBbox(), 4);
            double maxSize = base.getMaxSize();
            boolean bold = base.isBold();
            Line anchor = base;
            for (int j = i + 1; j < sorted.size(); j++) {
                if (used[j]) continue;
                Line next = sorted.get(j);
                double gap = next.getY0() - anchor.getY1();
                if (gap > bodyMedian * 2.4) break;
                if (gap < -bodyMedian * 0.6) continue;
                double overlap = PdfColumnSupport.horizontalOverlapRatio(anchor, next);
                double leftDelta = Math.abs(anchor.getX0() - next.getX0());
                double sizeRatio = Math.min(anchor.getMaxSize(), next.getMaxSize()) / Math.max(anchor.getMaxSize(), next.getMaxSize());
                boolean closeInX = leftDelta <= pageWidth * 0.045;
                boolean similarSize = sizeRatio >= 0.80;
                if (!(overlap >= 0.50 || closeInX) || !similarSize) continue;
                used[j] = true;
                String nextText = next.getText() == null ? "" : next.getText().trim();
                if (!nextText.isEmpty()) {
                    if (text.length() > 0) text.append("\n");
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

    static boolean shouldExpandToRightAdjacentColumn(Line title, int columnIndex, List<Double> splits, List<Line> allLines, Map<Integer, List<Line>> titlesByCol, double bodyMedian) {
        int lastCol = splits.size();
        if (columnIndex >= lastCol) return false;
        double colX0 = (columnIndex == 0) ? 0 : splits.get(columnIndex - 1);
        double colX1 = splits.get(columnIndex);
        double colWidth = Math.max(1.0, colX1 - colX0);
        if (title.getWidth() < colWidth * 0.72) return false;
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
        if (hasCompetingTitleNearTop(rightCol, probeTop, bodyMedian, titlesByCol)) return false;
        double rightGuardTop = titleY0 - bodyMedian * 2.0;
        double rightGuardBottom = probeTop + bodyMedian * 18.0;
        boolean hasRightCompetingTitle = titlesByCol.getOrDefault(rightCol, Collections.emptyList()).stream()
                .anyMatch(t -> t != title && t.getY0() >= rightGuardTop && t.getY0() <= rightGuardBottom);
        if (hasRightCompetingTitle) return false;
        boolean reachesBoundary = title.getBbox()[2] >= colX1 - colWidth * 0.08;
        if (!reachesBoundary) return false;
        return rightBodyLines >= 3;
    }

    static double findNextTitleTopInColumn(List<Line> colTitles, double currentTitleY) {
        double next = Double.MAX_VALUE;
        for (Line t : colTitles) if (t.getY0() > currentTitleY && t.getY0() < next) next = t.getY0();
        return next;
    }

    private static boolean isLikelyEmbeddedSubheading(Line candidate, List<Line> lines, double bodyMedian, double pageWidth) {
        if (isStrongHeadlineShape(candidate, bodyMedian, pageWidth)) return false;
        if (isLikelyTopIndependentArticleTitle(candidate, lines, bodyMedian, pageWidth)) return false;
        double size = candidate.getMaxSize();
        boolean onlySlightlyBigger = size < bodyMedian * 1.28;
        boolean narrowWidth = candidate.getWidth() < pageWidth * 0.36;
        Line nearestAbove = null, nearestBelow = null;
        for (Line line : lines) {
            if (line == candidate) continue;
            if (PdfColumnSupport.horizontalOverlapRatio(line, candidate) < 0.45) continue;
            if (line.getY1() <= candidate.getY0()) {
                if (nearestAbove == null || line.getY1() > nearestAbove.getY1()) nearestAbove = line;
            } else if (line.getY0() >= candidate.getY1()) {
                if (nearestBelow == null || line.getY0() < nearestBelow.getY0()) nearestBelow = line;
            }
        }
        if (nearestAbove == null || nearestBelow == null) return false;
        double topGap = candidate.getY0() - nearestAbove.getY1();
        double bottomGap = nearestBelow.getY0() - candidate.getY1();
        boolean tightlyEmbedded = topGap >= 0 && bottomGap >= 0 && topGap < bodyMedian * 0.95 && bottomGap < bodyMedian * 1.15;
        return onlySlightlyBigger && narrowWidth && tightlyEmbedded;
    }

    
    private static boolean isLikelyTopIndependentArticleTitle(Line candidate, List<Line> allLines, double bodyMedian, double pageWidth) {
        if (candidate == null || allLines == null || allLines.isEmpty()) return false;
        String text = candidate.getText() == null ? "" : candidate.getText().trim();
        if (text.length() < 8 || text.length() > 80) return false;
        if (SUBHEADING_MARKER_PATTERN.matcher(text).matches()) return false;
        if (REPORTER_EMAIL_PATTERN.matcher(text).find() || text.contains("@")) return false;

        double pageBottom = allLines.stream().mapToDouble(Line::getY1).max().orElse(candidate.getY1());
        double topZone = pageBottom * 0.38;
        if (candidate.getY0() > topZone) return false;

        boolean headlineSized = candidate.getMaxSize() >= bodyMedian * 1.20;
        boolean headlineWidth = candidate.getWidth() >= pageWidth * 0.22;
        if (!headlineSized || !headlineWidth) return false;

        double x0 = candidate.getBbox()[0];
        double x1 = candidate.getBbox()[2];
        double probeTop = candidate.getY1();
        double probeBottom = probeTop + bodyMedian * 16.0;

        long belowBodyLikeLines = allLines.stream()
                .filter(l -> l != candidate)
                .filter(l -> l.getY0() >= probeTop && l.getY0() <= probeBottom)
                .filter(l -> PdfColumnSupport.horizontalOverlapRatio(l, candidate) >= 0.40
                        || overlapRatioByRange(x0, x1, l.getBbox()[0], l.getBbox()[2]) >= 0.40)
                .filter(l -> l.getText() != null && l.getText().trim().length() >= 6)
                .filter(l -> l.getMaxSize() <= bodyMedian * 1.18)
                .count();

        return belowBodyLikeLines >= 4;
    }

    private static double overlapRatioByRange(double a0, double a1, double b0, double b1) {
        double overlap = Math.max(0.0, Math.min(a1, b1) - Math.max(a0, b0));
        double base = Math.max(1.0, Math.min(a1 - a0, b1 - b0));
        return overlap / base;
    }

    private static boolean isStrongHeadlineShape(Line candidate, double bodyMedian, double pageWidth) {
        if (candidate == null) return false;
        String text = candidate.getText() == null ? "" : candidate.getText().trim();
        if (text.length() < 8) return false;
        boolean sizeStrong = candidate.getMaxSize() >= bodyMedian * STRONG_HEADLINE_RATIO;
        boolean widthStrong = candidate.getWidth() >= pageWidth * STRONG_HEADLINE_MIN_WIDTH_RATIO;
        return sizeStrong && widthStrong;
    }

    private static boolean isLikelyNewArticleTitleAfterReporter(Line line, double bodyMedian, double pageWidth) {
        if (line == null) return false;
        String text = line.getText() == null ? "" : line.getText().trim();
        if (text.length() < 8 || text.length() > 90) return false;
        if (text.startsWith("\u25B6") || text.startsWith("\u25B7")) return false;
        if (SUBHEADING_MARKER_PATTERN.matcher(text).matches()) return false;
        if (REPORTER_EMAIL_PATTERN.matcher(text).find() || text.contains("@")) return false;
        boolean strongBySize = line.getMaxSize() >= bodyMedian * 1.33;
        boolean strongByBold = line.isBold() && line.getMaxSize() >= bodyMedian * 1.24;
        boolean enoughWidth = line.getWidth() >= pageWidth * 0.22;
        return (strongBySize || strongByBold) && enoughWidth;
    }

    private static boolean isRelaxedUpperHeadlineCandidate(Line line, double bodyMedian, double pageWidth) {
        String text = line.getText() == null ? "" : line.getText().trim();
        if (text.length() < 8 || text.length() > 80) return false;
        if (text.startsWith("\u25B6") || text.startsWith("\u25B7")) return false;
        if (SUBHEADING_MARKER_PATTERN.matcher(text).matches()) return false;
        if (REPORTER_EMAIL_PATTERN.matcher(text).find() || text.contains("@")) return false;
        boolean sizeStrong = line.getMaxSize() >= bodyMedian * 1.30 || (line.isBold() && line.getMaxSize() >= bodyMedian * 1.20);
        boolean widthEnough = line.getWidth() >= pageWidth * 0.20;
        return sizeStrong && widthEnough;
    }

    private static List<Line> restoreTopTitlesPerColumn(List<Line> originalCandidates, List<Line> filteredCandidates, double pageWidth) {
        List<Double> xs = originalCandidates.stream().map(Line::getX0).collect(Collectors.toList());
        List<Double> splits = PdfColumnSupport.inferColumnSplits(xs, pageWidth);
        Map<Integer, Line> topByColumn = new HashMap<>();
        for (Line line : originalCandidates) {
            int col = PdfColumnSupport.assignCol(line.getX0(), splits);
            Line current = topByColumn.get(col);
            if (current == null || line.getY0() < current.getY0() || (Math.abs(line.getY0() - current.getY0()) < 1.0 && line.getX0() < current.getX0())) {
                topByColumn.put(col, line);
            }
        }
        Set<Line> restored = new LinkedHashSet<>(filteredCandidates);
        restored.addAll(topByColumn.values());
        List<Line> result = new ArrayList<>(restored);
        result.sort(Comparator.comparingDouble(Line::getY0).thenComparingDouble(Line::getX0));
        return result;
    }

    private static List<Line> restoreStandaloneDroppedTitles(List<Line> originalCandidates, List<Line> filteredCandidates, List<Line> allLines, double bodyMedian, double pageWidth) {
        Set<Line> kept = new LinkedHashSet<>(filteredCandidates);
        for (Line candidate : originalCandidates) {
            if (kept.contains(candidate)) continue;
            if (isLikelyStandaloneTitleBlock(candidate, allLines, bodyMedian, pageWidth)) kept.add(candidate);
        }
        List<Line> result = new ArrayList<>(kept);
        result.sort(Comparator.comparingDouble(Line::getY0).thenComparingDouble(Line::getX0));
        return result;
    }

    private static boolean isLikelyStandaloneTitleBlock(Line candidate, List<Line> allLines, double bodyMedian, double pageWidth) {
        String text = candidate.getText() == null ? "" : candidate.getText().trim();
        if (text.length() < 9 || text.length() > 80) return false;
        if (SUBHEADING_MARKER_PATTERN.matcher(text).matches()) return false;
        boolean strongSize = candidate.getMaxSize() >= bodyMedian * 1.42 || (candidate.isBold() && candidate.getMaxSize() >= bodyMedian * 1.30);
        boolean enoughWidth = candidate.getWidth() >= pageWidth * 0.25;
        if (!strongSize || !enoughWidth) return false;
        Line nearestAbove = null, nearestBelow = null;
        for (Line line : allLines) {
            if (line == candidate || PdfColumnSupport.horizontalOverlapRatio(line, candidate) < 0.40) continue;
            if (line.getY1() <= candidate.getY0()) {
                if (nearestAbove == null || line.getY1() > nearestAbove.getY1()) nearestAbove = line;
            } else if (line.getY0() >= candidate.getY1()) {
                if (nearestBelow == null || line.getY0() < nearestBelow.getY0()) nearestBelow = line;
            }
        }
        if (nearestAbove == null || nearestBelow == null) return false;
        double topGap = candidate.getY0() - nearestAbove.getY1();
        double bottomGap = nearestBelow.getY0() - candidate.getY1();
        return topGap >= bodyMedian * 1.55 && bottomGap >= bodyMedian * 1.0;
    }

    private static boolean hasCompetingTitleNearTop(int columnIndex, double y0, double bodyMedian, Map<Integer, List<Line>> titlesByCol) {
        List<Line> colTitles = titlesByCol.getOrDefault(columnIndex, Collections.emptyList());
        double thresholdY = y0 + bodyMedian * 6.0;
        for (Line title : colTitles) if (title.getY0() > y0 && title.getY0() <= thresholdY) return true;
        return false;
    }
}
