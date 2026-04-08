package com.qroad.be.pdf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class PdfArticleDeduplicator {

    private PdfArticleDeduplicator() {
    }

    /**
     * 제목/본문/영역 겹침 기준으로 중복 기사 후보를 제거합니다.
     * 중복 시에는 본문 길이가 더 긴 기사를 우선 유지합니다.
     */
    static List<PdfArticle> removeDuplicateArticles(List<PdfArticle> articles) {
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

    private static String normalizeForCompare(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\uac00-\\ud7a3]+", "");
    }

    private static boolean isHighContainment(String a, String b) {
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

    private static double bboxOverlapRatio(double[] a, double[] b) {
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
}
