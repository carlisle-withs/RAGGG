package com.rag.domain.chunking;

import com.rag.domain.model.Chunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class StructuralChunkStrategy implements ChunkStrategy {

    private int minParagraphLength = 300;
    private int maxParagraphLength = 2000;

    @Override
    public List<Chunk> chunk(String text, String documentId, String kbId) {
        List<Chunk> chunks = new ArrayList<>();

        // Split by double newlines (paragraphs) or single newlines (lines)
        String[] segments = text.split("\n\\s*\n|\n");

        StringBuilder buffer = new StringBuilder();
        int chunkIndex = 0;

        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) continue;

            // If adding this segment exceeds max, flush buffer first
            if (buffer.length() + trimmed.length() > maxParagraphLength && buffer.length() > 0) {
                chunks.add(createChunk(buffer.toString(), documentId, kbId, chunkIndex++));
                buffer = new StringBuilder();
            }

            buffer.append(trimmed).append("\n");

            // If buffer reaches min length, create a chunk
            if (buffer.length() >= minParagraphLength) {
                chunks.add(createChunk(buffer.toString(), documentId, kbId, chunkIndex++));
                buffer = new StringBuilder();
            }
        }

        // Flush remaining content
        if (buffer.length() > 0) {
            chunks.add(createChunk(buffer.toString(), documentId, kbId, chunkIndex));
        }

        return chunks;
    }

    @Override
    public String getStrategyName() {
        return "structural";
    }

    public void setMinParagraphLength(int minParagraphLength) {
        this.minParagraphLength = minParagraphLength;
    }

    public void setMaxParagraphLength(int maxParagraphLength) {
        this.maxParagraphLength = maxParagraphLength;
    }

    private Chunk createChunk(String content, String documentId, String kbId, int chunkIndex) {
        Chunk chunk = new Chunk();
        chunk.setId(UUID.randomUUID().toString());
        chunk.setDocumentId(documentId);
        chunk.setContent(content.trim());
        chunk.setChunkIndex(chunkIndex);
        chunk.setTokenCount(content.length() / 4);
        chunk.getMetadata().put("kbId", kbId);
        chunk.getMetadata().put("chunkStrategy", "structural");
        return chunk;
    }
}
