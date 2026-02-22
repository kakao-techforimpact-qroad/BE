package com.qroad.be.pdf;

/**
 * Represents a single text line extracted from a PDF page.
 * Coordinates use top-left origin (y increases downward), matching PyMuPDF
 * convention.
 *
 * 원본: article-extractor/src/main/java/com/article/extractor/model/Line.java
 */
public class Line {
    private String text;
    private double[] bbox; // {x0, y0, x1, y1}
    private double maxSize;
    private double x0;
    private double y0;
    private double y1;
    private double width;

    public Line(String text, double[] bbox, double maxSize) {
        this.text = text;
        this.bbox = bbox;
        this.maxSize = maxSize;
        this.x0 = bbox[0];
        this.y0 = bbox[1];
        this.y1 = bbox[3];
        this.width = bbox[2] - bbox[0];
    }

    public String getText() {
        return text;
    }

    public double[] getBbox() {
        return bbox;
    }

    public double getMaxSize() {
        return maxSize;
    }

    public double getX0() {
        return x0;
    }

    public double getY0() {
        return y0;
    }

    public double getY1() {
        return y1;
    }

    public double getWidth() {
        return width;
    }
}
