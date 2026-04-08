package com.qroad.be.pdf;

import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class PdfColumnSupport {

    private PdfColumnSupport() {
    }

    /**
     * 제목/본문 x좌표 분포를 기반으로 컬럼 경계(splits)를 추정합니다.
     */
    static List<Double> inferColumnSplits(List<Double> xs, double pageWidth) {
        if (xs.size() < 4) return Collections.emptyList();
        List<Double> sorted = new ArrayList<>(xs);
        Collections.sort(sorted);
        List<double[]> gaps = new ArrayList<>();
        for (int i = 0; i < sorted.size() - 1; i++) gaps.add(new double[]{sorted.get(i + 1) - sorted.get(i), i});
        gaps.sort((a, b) -> Double.compare(b[0], a[0]));
        double threshold = pageWidth * 0.10;
        List<Double> splits = new ArrayList<>();
        for (int g = 0; g < Math.min(6, gaps.size()); g++) {
            double gap = gaps.get(g)[0];
            int idx = (int) gaps.get(g)[1];
            if (gap < threshold) break;
            double s = (sorted.get(idx) + sorted.get(idx + 1)) / 2.0;
            long left = xs.stream().filter(x -> x < s).count();
            long right = xs.size() - left;
            if (left < 4 || right < 4) continue;
            boolean tooClose = splits.stream().anyMatch(e -> Math.abs(s - e) < pageWidth * 0.05);
            if (tooClose) continue;
            splits.add(s);
            if (splits.size() >= 2) break;
        }
        Collections.sort(splits);
        return splits;
    }

    /**
     * 현재 x좌표가 몇 번째 컬럼에 속하는지 반환합니다.
     */
    static int assignCol(double x0, List<Double> splits) {
        if (splits.isEmpty()) return 0;
        for (int i = 0; i < splits.size(); i++) {
            if (x0 < splits.get(i)) return i;
        }
        return splits.size();
    }

    /**
     * bbox가 페이지 경계를 넘지 않도록 보정합니다.
     */
    static double[] clampBbox(double[] b, PDRectangle rect) {
        double x0 = Math.max(0, b[0]);
        double y0 = Math.max(0, b[1]);
        double x1 = Math.min(rect.getWidth(), b[2]);
        double y1 = Math.min(rect.getHeight(), b[3]);
        return new double[]{x0, y0, x1, y1};
    }

    /**
     * 라인의 중심점이 bbox 내부에 있는 라인만 선택합니다.
     */
    static List<Line> linesInBbox(List<Line> lines, double[] bbox) {
        double bx0 = bbox[0], by0 = bbox[1], bx1 = bbox[2], by1 = bbox[3];
        List<Line> picked = new ArrayList<>();
        for (Line l : lines) {
            double cx = (l.getBbox()[0] + l.getBbox()[2]) / 2.0;
            double cy = (l.getBbox()[1] + l.getBbox()[3]) / 2.0;
            if (cx >= bx0 && cx <= bx1 && cy >= by0 && cy <= by1) picked.add(l);
        }
        return picked;
    }

    /**
     * 본문 내부에서 제목 시작 x축에 맞는 하위 컬럼만 남깁니다.
     * 다단 본문에서 다른 기사 라인이 섞이는 현상을 줄이기 위한 필터입니다.
     */
    static List<Line> filterBodyLinesByAnchorSubColumn(List<Line> bodyLines, double articleX0, double articleX1, double titleX0, boolean allowRightAdjacentSubColumn) {
        if (bodyLines.size() < 8) return bodyLines;
        List<Double> xs = bodyLines.stream().map(Line::getX0).collect(Collectors.toList());
        double localWidth = Math.max(1.0, articleX1 - articleX0);
        List<Double> localSplits = inferColumnSplits(xs, localWidth);
        if (localSplits.isEmpty()) return bodyLines;
        int anchorCol = assignCol(titleX0, localSplits);
        List<Line> filtered = bodyLines.stream().filter(line -> {
            int c = assignCol(line.getX0(), localSplits);
            if (c == anchorCol) return true;
            return allowRightAdjacentSubColumn && c == anchorCol + 1;
        }).collect(Collectors.toList());
        return filtered.size() >= 4 ? filtered : bodyLines;
    }

    /**
     * 컬럼 단위로 정렬한 뒤 좌->우, 상->하 읽기 순서로 반환합니다.
     */
    static List<Line> sortLinesReadingOrder(List<Line> lines, double pageWidth) {
        if (lines.size() <= 2) {
            List<Line> copy = new ArrayList<>(lines);
            copy.sort(Comparator.comparingDouble(Line::getY0).thenComparingDouble(Line::getX0));
            return copy;
        }
        List<Double> xs = lines.stream().map(Line::getX0).collect(Collectors.toList());
        List<Double> splits = inferColumnSplits(xs, pageWidth);
        Map<Integer, List<Line>> cols = new TreeMap<>();
        for (Line l : lines) cols.computeIfAbsent(assignCol(l.getX0(), splits), k -> new ArrayList<>()).add(l);
        for (List<Line> colLines : cols.values()) {
            colLines.sort(Comparator.comparingDouble(Line::getY0).thenComparingDouble(Line::getX0));
        }
        List<Line> ordered = new ArrayList<>();
        for (List<Line> colLines : cols.values()) ordered.addAll(colLines);
        return ordered;
    }

    static double horizontalOverlapRatio(Line a, Line b) {
        double ax0 = a.getBbox()[0], ax1 = a.getBbox()[2];
        double bx0 = b.getBbox()[0], bx1 = b.getBbox()[2];
        double inter = Math.max(0, Math.min(ax1, bx1) - Math.max(ax0, bx0));
        double minWidth = Math.max(1.0, Math.min(ax1 - ax0, bx1 - bx0));
        return inter / minWidth;
    }

    static double[] unionBbox(List<Line> lines) {
        if (lines == null || lines.isEmpty()) return new double[]{0, 0, 0, 0};
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = 0, y1 = 0;
        for (Line l : lines) {
            x0 = Math.min(x0, l.getBbox()[0]);
            y0 = Math.min(y0, l.getBbox()[1]);
            x1 = Math.max(x1, l.getBbox()[2]);
            y1 = Math.max(y1, l.getBbox()[3]);
        }
        return new double[]{x0, y0, x1, y1};
    }

    /**
     * 한 컬럼으로 오판된 경우 큰 간격 기준으로 분할선을 보정합니다.
     */
    static List<Double> patchSplitsIfSingleColumnMisdetected(List<Line> titles, List<Double> splits, double pageWidth) {
        if (titles == null || titles.size() < 2) return splits;
        if (titles.stream().map(t -> assignCol(t.getX0(), splits)).collect(Collectors.toSet()).size() > 1) return splits;
        List<Double> xs = titles.stream().map(Line::getX0).sorted().collect(Collectors.toList());
        double maxGap = 0.0;
        int maxGapIdx = -1;
        for (int i = 0; i < xs.size() - 1; i++) {
            double gap = xs.get(i + 1) - xs.get(i);
            if (gap > maxGap) {
                maxGap = gap;
                maxGapIdx = i;
            }
        }
        if (maxGapIdx < 0 || maxGap < pageWidth * 0.18) return splits;
        double newSplit = (xs.get(maxGapIdx) + xs.get(maxGapIdx + 1)) / 2.0;
        List<Double> patched = new ArrayList<>(splits);
        patched.add(newSplit);
        return patched.stream().distinct().sorted().collect(Collectors.toList());
    }

    static boolean isInsideBboxCenter(Line line, double[] bbox) {
        double cx = (line.getBbox()[0] + line.getBbox()[2]) / 2.0;
        double cy = (line.getBbox()[1] + line.getBbox()[3]) / 2.0;
        return cx >= bbox[0] && cx <= bbox[2] && cy >= bbox[1] && cy <= bbox[3];
    }

    static boolean overlapsArticleTitleArea(Line line, PdfArticle article) {
        if (article.getTitleBbox() == null) return false;
        double[] lb = line.getBbox();
        double[] tb = article.getTitleBbox();
        double interX = Math.max(0, Math.min(lb[2], tb[2]) - Math.max(lb[0], tb[0]));
        double minW = Math.max(1.0, Math.min(lb[2] - lb[0], tb[2] - tb[0]));
        double overlapRatio = interX / minW;
        boolean yNear = lb[1] <= tb[3] + 2 && lb[3] >= tb[1] - 2;
        return overlapRatio >= 0.5 && yNear;
    }
}
