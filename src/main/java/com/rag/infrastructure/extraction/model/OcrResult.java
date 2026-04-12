package com.rag.infrastructure.extraction.model;

import java.util.List;

public class OcrResult {

    private String text;
    private float confidence;
    private List<TextBlock> blocks;
    private int pageNumber;
    private boolean success;
    private String errorMessage;

    public OcrResult() {
        this.success = true;
    }

    public static class TextBlock {
        private String text;
        private int x;
        private int y;
        private int width;
        private int height;

        public TextBlock() {}

        public TextBlock(String text, int x, int y, int width, int height) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public int getX() { return x; }
        public void setX(int x) { this.x = x; }
        public int getY() { return y; }
        public void setY(int y) { this.y = y; }
        public int getWidth() { return width; }
        public void setWidth(int width) { this.width = width; }
        public int getHeight() { return height; }
        public void setHeight(int height) { this.height = height; }
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public float getConfidence() { return confidence; }
    public void setConfidence(float confidence) { this.confidence = confidence; }
    public List<TextBlock> getBlocks() { return blocks; }
    public void setBlocks(List<TextBlock> blocks) { this.blocks = blocks; }
    public int getPageNumber() { return pageNumber; }
    public void setPageNumber(int pageNumber) { this.pageNumber = pageNumber; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public static OcrResult failure(String errorMessage) {
        OcrResult result = new OcrResult();
        result.setSuccess(false);
        result.setErrorMessage(errorMessage);
        return result;
    }
}