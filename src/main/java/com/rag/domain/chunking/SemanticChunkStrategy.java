package com.rag.domain.chunking;

import com.rag.domain.model.Chunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class SemanticChunkStrategy implements ChunkStrategy {

    private int maxTokensPerChunk = 512;

    private static final String[] SENTENCE_DELIMITERS = {
            "。", "！", "？", ".", "!", "?"
    };

    @Override
    public List<Chunk> chunk(String text, String documentId, String kbId) {
        // Step 1: Split text into sentences
        List<String> sentences = splitIntoSentences(text);

        // Step 2: Group sentences into chunks based on token count
        List<String> chunkContents = groupSentencesToChunks(sentences);

        // Step 3: Convert to Chunks
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < chunkContents.size(); i++) {
            String content = chunkContents.get(i);
            chunks.add(createChunk(content, documentId, kbId, i));
        }

        return chunks;
    }

    private List<String> groupSentencesToChunks(List<String> sentences) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;

        for (String sentence : sentences) {
            int sentenceTokens = sentence.length() / 4;

            // If adding this sentence exceeds max AND current chunk is not empty, start new chunk
            if (currentTokens + sentenceTokens > maxTokensPerChunk && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
                currentTokens = 0;
            }

            currentChunk.append(sentence);
            currentTokens += sentenceTokens;

            // If single sentence exceeds max, it becomes its own chunk
            if (currentTokens > maxTokensPerChunk && currentChunk.length() == sentence.length()) {
                chunks.add(sentence);
                currentChunk = new StringBuilder();
                currentTokens = 0;
            }
        }

        // Flush remaining
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
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

    @Override
    public String getStrategyName() {
        return "semantic";
    }

    public void setMaxTokensPerChunk(int maxTokensPerChunk) {
        this.maxTokensPerChunk = maxTokensPerChunk;
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
