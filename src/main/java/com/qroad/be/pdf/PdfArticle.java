package com.qroad.be.pdf;

/**
 * PDF에서 추출된 기사 단위를 나타냅니다.
 *
 * 원본: article-extractor/src/main/java/com/article/extractor/model/Article.java
 */
public class PdfArticle {
    private String id;
    private String title;
    private double[] titleBbox;
    private double[] bodyBbox;
    private String text;
    private Integer continuationToPage;
    private int columnIndex;
    private int page;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public double[] getTitleBbox() {
        return titleBbox;
    }

    public void setTitleBbox(double[] titleBbox) {
        this.titleBbox = titleBbox;
    }

    public double[] getBodyBbox() {
        return bodyBbox;
    }

    public void setBodyBbox(double[] bodyBbox) {
        this.bodyBbox = bodyBbox;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Integer getContinuationToPage() {
        return continuationToPage;
    }

    public void setContinuationToPage(Integer continuationToPage) {
        this.continuationToPage = continuationToPage;
    }

    public int getColumnIndex() {
        return columnIndex;
    }

    public void setColumnIndex(int columnIndex) {
        this.columnIndex = columnIndex;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }
}
