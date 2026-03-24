package com.rag.domain.chunking;

import com.rag.domain.model.Chunk;
import com.rag.infrastructure.llm.EmbeddingService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class SemanticChunkStrategy implements ChunkStrategy {

    private final EmbeddingService embeddingService;
    private int maxTokensPerChunk = 512;
    private double similarityThreshold = 0.7;

    private static final String[] SENTENCE_DELIMITERS = {
        "。", "！", "？", ".", "!", "?"
    };

    public SemanticChunkStrategy(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @Override
    public List<Chunk> chunk(String text, String documentId, String kbId) {
        List<Chunk> chunks = new ArrayList<>();

        // Split into sentences
        List<String> sentences = splitIntoSentences(text);

        StringBuilder currentChunk = new StringBuilder();
        float[] previousEmbedding = null;
        int chunkIndex = 0;

        for (String sentence : sentences) {
            String trialChunk = currentChunk.length() > 0
                ? currentChunk.toString() + sentence
                : sentence;

            int estimatedTokens = trialChunk.length() / 4;

            // If exceeds max tokens, save current and start new
            if (estimatedTokens > maxTokensPerChunk && currentChunk.length() > 0) {
                chunks.add(createChunk(currentChunk.toString(), documentId, kbId, chunkIndex++));

                // Calculate similarity with previous chunk for semantic continuity
                float[] currentEmbedding = embeddingService.embed(currentChunk.toString());
                if (previousEmbedding != null) {
                    float similarity = cosineSimilarity(previousEmbedding, currentEmbedding);
                    // If similarity is low, mark as potential topic change
                    if (similarity < similarityThreshold) {
                        // Start fresh without carry-over
                        currentChunk = new StringBuilder();
                    }
                }
                previousEmbedding = embeddingService.embed(currentChunk.toString());
                currentChunk = new StringBuilder();
            }

            currentChunk.append(sentence);
        }

        // Flush remaining
        if (currentChunk.length() > 0) {
            chunks.add(createChunk(currentChunk.toString(), documentId, kbId, chunkIndex));
        }

        return chunks;
    }

    private List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);

            // Check for sentence delimiter
            for (String delimiter : SENTENCE_DELIMITERS) {
                if (text.regionMatches(i, delimiter, 0, delimiter.length())) {
                    sentences.add(current.toString());
                    current = new StringBuilder();
                    break;
                }
            }
        }

        if (current.length() > 0) {
            sentences.add(current.toString());
        }

        return sentences;
    }

    private float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        float dotProduct = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return (float) (dotProduct / (Math.sqrt(normA) * Math.sqrt(normB) + 1e-10));
    }

    @Override
    public String getStrategyName() {
        return "semantic";
    }

    public void setMaxTokensPerChunk(int maxTokensPerChunk) {
        this.maxTokensPerChunk = maxTokensPerChunk;
    }

    public void setSimilarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    private Chunk createChunk(String content, String documentId, String kbId, int chunkIndex) {
        Chunk chunk = new Chunk();
        chunk.setId(UUID.randomUUID().toString());
        chunk.setDocumentId(documentId);
        chunk.setContent(content.trim());
        chunk.setChunkIndex(chunkIndex);
        chunk.setTokenCount(content.length() / 4);
        chunk.getMetadata().put("kbId", kbId);
        chunk.getMetadata().put("chunkStrategy", "semantic");
        return chunk;
    }
}
