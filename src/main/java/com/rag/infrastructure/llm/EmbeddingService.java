package com.rag.infrastructure.llm;

import com.rag.config.AppConfig;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingModel embeddingModel;
    private final int dimension;

    public EmbeddingService(AppConfig appConfig) {
        this.dimension = appConfig.getEmbedding().getDimension();
        this.embeddingModel = OpenAiEmbeddingModel.builder()
                .baseUrl(appConfig.getEmbedding().getBaseUrl())
                .apiKey(appConfig.getEmbedding().getApiKey())
                .modelName(appConfig.getEmbedding().getModel())
                .build();
    }

    public float[] embed(String text) {
        try {
            Embedding embedding = embeddingModel.embed(text).content();
            float[] result = new float[embedding.vector().length];
            System.arraycopy(embedding.vector(), 0, result, 0, embedding.vector().length);
            return result;
        } catch (Exception e) {
            log.error("Failed to generate embedding", e);
            throw new RuntimeException("Embedding failed", e);
        }
    }

    public int getDimension() {
        return dimension;
    }
}
