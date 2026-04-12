package com.rag.infrastructure.extraction.service;

import com.rag.config.AppConfig;
import com.rag.domain.model.Chunk;
import com.rag.infrastructure.extraction.model.ImageContent;
import com.rag.infrastructure.extraction.model.TableContent;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EnhancedContentProcessor {

    private static final Logger log = LoggerFactory.getLogger(EnhancedContentProcessor.class);

    private final Tika tika;
    private final AutoDetectParser parser;
    private final ImageOcrService imageOcrService;
    private final TableParserService tableParserService;
    private final AppConfig.Extraction extractionConfig;

    public EnhancedContentProcessor(ImageOcrService imageOcrService,
                                     TableParserService tableParserService,
                                     AppConfig appConfig) {
        this.tika = new Tika();
        this.parser = new AutoDetectParser();
        this.imageOcrService = imageOcrService;
        this.tableParserService = tableParserService;
        this.extractionConfig = appConfig.getExtraction();
        log.info("EnhancedContentProcessor initialized: enabled={}", extractionConfig.isEnabled());
    }

    public EnhancementResult process(byte[] documentData, String documentId, String kbId) {
        EnhancementResult result = new EnhancementResult();

        if (!extractionConfig.isEnabled()) {
            log.debug("Enhanced processing is disabled");
            return result;
        }

        try {
            log.debug("Starting enhanced content extraction for document: {}", documentId);

            ExtractedContent extracted = extractContent(documentData);
            result.setTextContent(extracted.text);

            if (extractionConfig.getImage().isEnabled() && !extracted.images.isEmpty()) {
                log.debug("Processing {} images from document", extracted.images.size());
                List<ImageContent> processedImages = imageOcrService.extractAndProcessImages(extracted.images);
                result.setImages(processedImages);

                StringBuilder imageText = new StringBuilder();
                for (ImageContent img : processedImages) {
                    if (img.getOcrResult() != null && img.getOcrResult().isSuccess()) {
                        imageText.append("\n\n[图片 OCR - 第").append(img.getPageNumber()).append("页]: ");
                        imageText.append(img.getOcrResult().getText());
                    }
                }
                result.setExtractedImageText(imageText.toString());
            }

            if (extractionConfig.getTable().isEnabled() && !extracted.tables.isEmpty()) {
                log.debug("Processing {} tables from document", extracted.tables.size());
                List<TableContent> processedTables = tableParserService.parseTables(extracted.tables);
                result.setTables(processedTables);

                StringBuilder tableText = new StringBuilder();
                for (TableContent tbl : processedTables) {
                    tableText.append("\n\n[表格 - 第").append(tbl.getPageNumber()).append("页]: ");
                    if (tbl.getHtmlContent() != null) {
                        tableText.append("\n").append(tbl.getHtmlContent());
                    }
                }
                result.setExtractedTableText(tableText.toString());
            }

            log.info("Enhanced extraction complete: textLength={}, images={}, tables={}",
                    result.getTextContent().length(),
                    result.getImages().size(),
                    result.getTables().size());

        } catch (Exception e) {
            log.error("Enhanced content extraction failed: {}", e.getMessage(), e);
        }

        return result;
    }

    private ExtractedContent extractContent(byte[] documentData) throws IOException, TikaException, SAXException {
        ExtractedContent extracted = new ExtractedContent();

        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();

        try (InputStream stream = new ByteArrayInputStream(documentData)) {
            parser.parse(stream, handler, metadata, context);
        }

        extracted.text = handler.toString();

        String[] embeddedImages = metadata.getValues("X-TIKA:embedded_images");
        if (embeddedImages != null) {
            for (int i = 0; i < embeddedImages.length; i++) {
                String imageKey = "X-TIKA:embedded_image_" + i;
                String[] imageData = metadata.getValues(imageKey);
                if (imageData != null && imageData.length > 0) {
                    try {
                        byte[] data = java.util.Base64.getDecoder().decode(imageData[0]);
                        if (data.length > 0) {
                            ImageContent image = new ImageContent(
                                    UUID.randomUUID().toString(),
                                    data,
                                    0
                            );
                            image.setImageId("image_" + i);
                            extracted.images.add(image);
                        }
                    } catch (Exception e) {
                        log.debug("Failed to decode embedded image {}: {}", i, e.getMessage());
                    }
                }
            }
        }

        return extracted;
    }

    public List<Chunk> createChunksFromEnhancement(String text, String documentId, String kbId,
                                                     EnhancementResult enhancementResult) {
        List<Chunk> chunks = new ArrayList<>();

        StringBuilder fullContent = new StringBuilder(text);
        if (enhancementResult.getExtractedImageText() != null) {
            fullContent.append(enhancementResult.getExtractedImageText());
        }
        if (enhancementResult.getExtractedTableText() != null) {
            fullContent.append(enhancementResult.getExtractedTableText());
        }

        int chunkSize = 512;
        int chunkOverlap = 50;
        String content = fullContent.toString();
        int chunkIndex = 0;

        for (int i = 0; i < content.length(); i += chunkSize - chunkOverlap) {
            int end = Math.min(i + chunkSize, content.length());
            String chunkContent = content.substring(i, end);

            Chunk chunk = new Chunk(documentId, kbId, chunkContent, chunkIndex);
            Map<String, String> metadata = new HashMap<>();

            if (!enhancementResult.getImages().isEmpty()) {
                StringBuilder imgMeta = new StringBuilder();
                for (ImageContent img : enhancementResult.getImages()) {
                    if (imgMeta.length() > 0) imgMeta.append(",");
                    imgMeta.append(img.getImageId());
                }
                metadata.put("imageIds", imgMeta.toString());
            }

            if (!enhancementResult.getTables().isEmpty()) {
                StringBuilder tblMeta = new StringBuilder();
                for (TableContent tbl : enhancementResult.getTables()) {
                    if (tblMeta.length() > 0) tblMeta.append(",");
                    tblMeta.append(tbl.getTableId());
                }
                metadata.put("tableIds", tblMeta.toString());
            }

            chunk.setMetadata(metadata);
            chunks.add(chunk);
            chunkIndex++;

            if (end >= content.length()) break;
        }

        return chunks;
    }

    public boolean isEnabled() {
        return extractionConfig.isEnabled();
    }

    private static class ExtractedContent {
        String text;
        List<ImageContent> images = new ArrayList<>();
        List<TableContent> tables = new ArrayList<>();
    }

    public static class EnhancementResult {
        private String textContent;
        private String extractedImageText;
        private String extractedTableText;
        private List<ImageContent> images = new ArrayList<>();
        private List<TableContent> tables = new ArrayList<>();

        public String getTextContent() { return textContent; }
        public void setTextContent(String textContent) { this.textContent = textContent; }
        public String getExtractedImageText() { return extractedImageText; }
        public void setExtractedImageText(String extractedImageText) { this.extractedImageText = extractedImageText; }
        public String getExtractedTableText() { return extractedTableText; }
        public void setExtractedTableText(String extractedTableText) { this.extractedTableText = extractedTableText; }
        public List<ImageContent> getImages() { return images; }
        public void setImages(List<ImageContent> images) { this.images = images; }
        public List<TableContent> getTables() { return tables; }
        public void setTables(List<TableContent> tables) { this.tables = tables; }
    }
}