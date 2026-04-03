package com.qroad.be.pdf;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters;
import org.apache.pdfbox.contentstream.operator.state.SetMatrix;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ImagePositionExtractor extends PDFStreamEngine {

    public static class ImageBoundingBox {
        public PDImageXObject image;
        public double minX, minY, maxX, maxY;
        
        public ImageBoundingBox(PDImageXObject image, double minX, double minY, double maxX, double maxY) {
            this.image = image;
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }
    }

    private List<ImageBoundingBox> images = new ArrayList<>();
    private PDPage currentPage;

    public ImagePositionExtractor() {
        addOperator(new Concatenate(this));
        addOperator(new DrawObject(this));
        addOperator(new SetGraphicsStateParameters(this));
        addOperator(new Save(this));
        addOperator(new Restore(this));
        addOperator(new SetMatrix(this));
    }

    public List<ImageBoundingBox> extract(PDPage page) throws IOException {
        this.currentPage = page;
        this.images.clear();
        processPage(page);
        return new ArrayList<>(this.images);
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        if ("Do".equals(operator.getName())) {
            COSName objectName = (COSName) operands.get(0);
            PDXObject xobject = getResources().getXObject(objectName);
            
            if (xobject instanceof PDImageXObject) {
                PDImageXObject image = (PDImageXObject) xobject;
                Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
                
                // 좌표 변환 (CTM 사용)
                float x = ctm.getTranslateX();
                // PDFBox 3.0의 Y축 시작점을 AWT(위에서 아래)로 맞추기 위해 변환
                float pageHeight = currentPage.getCropBox().getHeight();
                float y = pageHeight - ctm.getTranslateY() - ctm.getScaleY();
                
                double minX = x;
                double minY = y;
                double maxX = x + ctm.getScaleX();
                double maxY = y + ctm.getScaleY();
                
                images.add(new ImageBoundingBox(image, minX, minY, maxX, maxY));
            }
        } else {
            super.processOperator(operator, operands);
        }
    }
}
