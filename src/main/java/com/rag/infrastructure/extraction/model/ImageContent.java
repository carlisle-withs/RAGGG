package com.rag.infrastructure.extraction.model;

public class ImageContent {

    private String imageId;
    private byte[] data;
    private int width;
    private int height;
    private int pageNumber;
    private String sourcePosition;
    private OcrResult ocrResult;

    public ImageContent() {}

    public ImageContent(String imageId, byte[] data, int pageNumber) {
        this.imageId = imageId;
        this.data = data;
        this.pageNumber = pageNumber;
    }

    public String getImageId() { return imageId; }
    public void setImageId(String imageId) { this.imageId = imageId; }
    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }
    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }
    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }
    public int getPageNumber() { return pageNumber; }
    public void setPageNumber(int pageNumber) { this.pageNumber = pageNumber; }
    public String getSourcePosition() { return sourcePosition; }
    public void setSourcePosition(String sourcePosition) { this.sourcePosition = sourcePosition; }
    public OcrResult getOcrResult() { return ocrResult; }
    public void setOcrResult(OcrResult ocrResult) { this.ocrResult = ocrResult; }

    public String getExtractedText() {
        return ocrResult != null ? ocrResult.getText() : "";
    }
}