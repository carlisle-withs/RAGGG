package com.rag.infrastructure.extraction.service;

import com.rag.config.AppConfig;
import com.rag.infrastructure.extraction.client.OcrClient;
import com.rag.infrastructure.extraction.model.ImageContent;
import com.rag.infrastructure.extraction.model.OcrResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class ImageOcrService {

    private static final Logger log = LoggerFactory.getLogger(ImageOcrService.class);

    private final List<OcrClient> ocrClients;
    private final AppConfig.Extraction extractionConfig;
    private final AppConfig.Extraction.Image imageConfig;

    public ImageOcrService(List<OcrClient> ocrClients, AppConfig appConfig) {
        this.ocrClients = ocrClients;
        this.extractionConfig = appConfig.getExtraction();
        this.imageConfig = extractionConfig.getImage();
        log.info("ImageOcrService initialized: enabled={}, minSize={}, clients={}",
                imageConfig.isEnabled(), imageConfig.getMinSize(), ocrClients.size());
    }

    public OcrResult extractTextFromImage(byte[] imageData) {
        if (!extractionConfig.isEnabled() || !imageConfig.isEnabled()) {
            log.debug("Image OCR is disabled, skipping");
            return OcrResult.failure("Image OCR is disabled");
        }

        OcrClient client = selectClient();
        if (client == null || !client.isEnabled()) {
            log.warn("No enabled OCR client found");
            return OcrResult.failure("No enabled OCR client");
        }

        if (!isValidImageSize(imageData)) {
            log.debug("Image too small, skipping OCR");
            return OcrResult.failure("Image too small");
        }

        log.debug("Using OCR client: {}", client.getProviderName());
        return client.extractText(imageData);
    }

    public List<ImageContent> extractAndProcessImages(List<ImageContent> images) {
        if (!extractionConfig.isEnabled() || !imageConfig.isEnabled()) {
            return images;
        }

        AppConfig.Extraction.Processing processingConfig = extractionConfig.getProcessing();

        if (processingConfig.isParallel()) {
            return images.parallelStream()
                    .map(this::processImage)
                    .collect(Collectors.toList());
        } else {
            return images.stream()
                    .map(this::processImage)
                    .collect(Collectors.toList());
        }
    }

    private ImageContent processImage(ImageContent image) {
        try {
            OcrResult result = extractTextFromImage(image.getData());
            image.setOcrResult(result);
            log.debug("Processed image: {}, text length: {}",
                    image.getImageId(),
                    result.isSuccess() ? result.getText().length() : 0);
        } catch (Exception e) {
            log.error("Failed to process image {}: {}", image.getImageId(), e.getMessage());
            image.setOcrResult(OcrResult.failure(e.getMessage()));
        }
        return image;
    }

    private OcrClient selectClient() {
        return ocrClients.stream()
                .filter(OcrClient::isEnabled)
                .findFirst()
                .orElse(null);
    }

    private boolean isValidImageSize(byte[] imageData) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageData));
            if (img == null) {
                return false;
            }
            int minSize = imageConfig.getMinSize();
            return img.getWidth() >= minSize && img.getHeight() >= minSize;
        } catch (IOException e) {
            log.warn("Failed to read image dimensions: {}", e.getMessage());
            return false;
        }
    }

    public boolean isEnabled() {
        return extractionConfig.isEnabled() && imageConfig.isEnabled();
    }
}