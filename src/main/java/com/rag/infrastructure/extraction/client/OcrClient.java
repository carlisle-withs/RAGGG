package com.rag.infrastructure.extraction.client;

import com.rag.infrastructure.extraction.model.OcrResult;

public interface OcrClient {

    OcrResult extractText(byte[] imageData);

    boolean isEnabled();

    String getProviderName();
}