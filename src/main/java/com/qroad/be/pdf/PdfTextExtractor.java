package com.qroad.be.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.StringWriter;
import java.util.*;

/**
 * Hybrid PDFTextStripper that combines:
 * - PDFTextStripper's writeString() for proper word spacing (handles TJ-based
 * spaces)
 * - Post-processing to split column-merged lines using TextPosition x-gaps
 *
 * getYDirAdj() returns top-left origin (0=top, increases downward),
 * matching PyMuPDF convention.
 *
 * 원본:
 * article-extractor/src/main/java/com/article/extractor/util/PdfTextExtractor.java
 */
public class PdfTextExtractor extends PDFTextStripper {

    private final float pageWidth;

    // Per-line accumulator
    private final List<Segment> currentSegments = new ArrayList<>();
    private boolean wordSepPending = false;

    // Output
    private final List<RawLine> rawLines = new ArrayList<>();
    private final List<Double> allFontSizes = new ArrayList<>();

    public PdfTextExtractor(float pageWidth) throws IOException {
        this.pageWidth = pageWidth;
        setSortByPosition(true);
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
        if (wordSepPending && !currentSegments.isEmpty()) {
            currentSegments.add(new Segment(" ", Collections.emptyList()));
            wordSepPending = false;
        }
        currentSegments.add(new Segment(text,
                textPositions != null ? new ArrayList<>(textPositions) : Collections.emptyList()));
    }

    @Override
    protected void writeWordSeparator() throws IOException {
        wordSepPending = true;
    }

    @Override
    protected void writeLineSeparator() throws IOException {
        flushLine();
    }

    private void flushLine() {
        if (currentSegments.isEmpty()) {
            resetLine();
            return;
        }

        // Build text and collect all positions with their text offsets
        StringBuilder sb = new StringBuilder();
        List<PosEntry> entries = new ArrayList<>();

        for (Segment seg : currentSegments) {
            if (seg.positions.isEmpty()) {
                // Word separator or text without positions
                sb.append(seg.text);
            } else {
                int textStart = sb.length();
                sb.append(seg.text);
                for (int i = 0; i < seg.positions.size(); i++) {
                    TextPosition tp = seg.positions.get(i);
                    if (tp.getUnicode() == null || tp.getUnicode().isBlank())
                        continue;
                    int charIdx = textStart + Math.min(i, seg.text.length() - 1);
                    entries.add(new PosEntry(tp, charIdx));
                }
            }
        }

        String fullText = sb.toString().replaceAll("\\s+", " ").trim();
        if (fullText.isEmpty() || entries.isEmpty()) {
            resetLine();
            return;
        }

        // Sort entries by x position
        entries.sort(Comparator.comparingDouble(e -> e.tp.getXDirAdj()));

        // Compute average character width
        double avgW = entries.stream()
                .mapToDouble(e -> e.tp.getWidthDirAdj())
                .filter(w -> w > 0)
                .average().orElse(5.0);

        // Detect column breaks (large gaps between consecutive positions)
        // Newspaper column gaps are typically 20-40pt; word spaces are ~1 char width
        double colGapThreshold = Math.max(avgW * 2, pageWidth * 0.02);

        List<Integer> splitPositionIndices = new ArrayList<>();
        for (int i = 1; i < entries.size(); i++) {
            double gap = entries.get(i).tp.getXDirAdj()
                    - (entries.get(i - 1).tp.getXDirAdj() + entries.get(i - 1).tp.getWidthDirAdj());
            if (gap > colGapThreshold) {
                splitPositionIndices.add(i);
            }
        }

        if (splitPositionIndices.isEmpty()) {
            // No column break — single line
            addRawLine(fullText, entries);
        } else {
            // Split into sub-lines at column breaks
            int prevIdx = 0;
            for (int splitIdx : splitPositionIndices) {
                List<PosEntry> subEntries = entries.subList(prevIdx, splitIdx);
                String subText = extractSubText(sb.toString(), subEntries);
                addRawLine(subText, subEntries);
                prevIdx = splitIdx;
            }
            // Last segment
            List<PosEntry> subEntries = entries.subList(prevIdx, entries.size());
            String subText = extractSubText(sb.toString(), subEntries);
            addRawLine(subText, subEntries);
        }

        resetLine();
    }

    private String extractSubText(String fullText, List<PosEntry> subEntries) {
        if (subEntries.isEmpty())
            return "";
        // Get character range from original text
        int minCharIdx = subEntries.stream().mapToInt(e -> e.charIdx).min().orElse(0);
        int maxCharIdx = subEntries.stream().mapToInt(e -> e.charIdx).max().orElse(fullText.length() - 1);
        // Extend to include the character at maxCharIdx
        int end = Math.min(maxCharIdx + 1, fullText.length());
        return fullText.substring(minCharIdx, end).replaceAll("\\s+", " ").trim();
    }

    private void addRawLine(String text, List<PosEntry> entries) {
        if (text.isEmpty() || entries.isEmpty())
            return;

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxFontSize = 0;

        for (PosEntry e : entries) {
            TextPosition tp = e.tp;
            float x = tp.getXDirAdj();
            float y = tp.getYDirAdj();
            float w = tp.getWidthDirAdj();
            float h = tp.getHeightDir();
            float fontSize = tp.getFontSizeInPt();

            double charTop = y - h;
            double charBottom = y;

            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x + w);
            minY = Math.min(minY, charTop);
            maxY = Math.max(maxY, charBottom);
            maxFontSize = Math.max(maxFontSize, fontSize);

            allFontSizes.add((double) fontSize);
        }

        rawLines.add(new RawLine(text, minX, minY, maxX, maxY, maxFontSize));
    }

    private void resetLine() {
        currentSegments.clear();
        wordSepPending = false;
    }

    public void finish() {
        flushLine();
    }

    // ── public API ───────────────────────────────────────────────

    public static ExtractResult extractLines(PDPage page, PDDocument document, int pageIndex) throws IOException {
        PDRectangle mediaBox = page.getMediaBox();
        float pageWidth = mediaBox.getWidth();

        PdfTextExtractor stripper = new PdfTextExtractor(pageWidth);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);

        StringWriter writer = new StringWriter();
        stripper.writeText(document, writer);
        stripper.finish();

        List<Line> lines = new ArrayList<>();
        for (RawLine rl : stripper.rawLines) {
            lines.add(new Line(rl.text, new double[] { rl.minX, rl.minY, rl.maxX, rl.maxY }, rl.maxFontSize));
        }
        // Sort top-to-bottom, left-to-right
        lines.sort(Comparator.comparingDouble(Line::getY0).thenComparingDouble(Line::getX0));

        return new ExtractResult(lines, stripper.allFontSizes);
    }

    // ── inner classes ────────────────────────────────────────────

    private static class Segment {
        final String text;
        final List<TextPosition> positions;

        Segment(String text, List<TextPosition> positions) {
            this.text = text;
            this.positions = positions;
        }
    }

    private static class PosEntry {
        final TextPosition tp;
        final int charIdx; // index in the full text StringBuilder

        PosEntry(TextPosition tp, int charIdx) {
            this.tp = tp;
            this.charIdx = charIdx;
        }
    }

    private static class RawLine {
        final String text;
        final double minX, minY, maxX, maxY, maxFontSize;

        RawLine(String text, double minX, double minY, double maxX, double maxY, double maxFontSize) {
            this.text = text;
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxFontSize = maxFontSize;
        }
    }

    public static class ExtractResult {
        private final List<Line> lines;
        private final List<Double> sizes;

        public ExtractResult(List<Line> lines, List<Double> sizes) {
            this.lines = lines;
            this.sizes = sizes;
        }

        public List<Line> getLines() {
            return lines;
        }

        public List<Double> getSizes() {
            return sizes;
        }
    }
}
