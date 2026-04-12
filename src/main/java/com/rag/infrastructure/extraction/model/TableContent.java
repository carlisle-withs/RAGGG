package com.rag.infrastructure.extraction.model;

public class TableContent {

    private String tableId;
    private byte[] data;
    private String htmlContent;
    private String jsonContent;
    private int rows;
    private int columns;
    private String[] headers;
    private int pageNumber;
    private String sourcePosition;
    private float confidence;

    public TableContent() {}

    public TableContent(String tableId, int pageNumber) {
        this.tableId = tableId;
        this.pageNumber = pageNumber;
    }

    public String getTableId() { return tableId; }
    public void setTableId(String tableId) { this.tableId = tableId; }
    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }
    public String getHtmlContent() { return htmlContent; }
    public void setHtmlContent(String htmlContent) { this.htmlContent = htmlContent; }
    public String getJsonContent() { return jsonContent; }
    public void setJsonContent(String jsonContent) { this.jsonContent = jsonContent; }
    public int getRows() { return rows; }
    public void setRows(int rows) { this.rows = rows; }
    public int getColumns() { return columns; }
    public void setColumns(int columns) { this.columns = columns; }
    public String[] getHeaders() { return headers; }
    public void setHeaders(String[] headers) { this.headers = headers; }
    public int getPageNumber() { return pageNumber; }
    public void setPageNumber(int pageNumber) { this.pageNumber = pageNumber; }
    public String getSourcePosition() { return sourcePosition; }
    public void setSourcePosition(String sourcePosition) { this.sourcePosition = sourcePosition; }
    public float getConfidence() { return confidence; }
    public void setConfidence(float confidence) { this.confidence = confidence; }
}