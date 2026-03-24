package com.rag.domain.chunking;

import com.rag.domain.model.Chunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class FixedChunkStrategy implements ChunkStrategy {

    private int chunkSize = 512;
    private int chunkOverlap = 50;

    @Override
    public List<Chunk> chunk(String text, String documentId, String kbId) {
        List<Chunk> chunks = new ArrayList<>();
        int index = 0;
        int chunkIndex = 0;

        while (index < text.length()) {
            int end = Math.min(index + chunkSize, text.length());
            String chunkText = text.substring(index, end);

            Chunk chunk = createChunk(new String(chunkText), documentId, kbId, chunkIndex);
            chunks.add(chunk);

            index = end - chunkOverlap;
            if (index <= 0) index = end;
            chunkIndex++;
        }

        return chunks;
    }

    @Override
    public String getStrategyName() {
        return "fixed";
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    private Chunk createChunk(String content, String documentId, String kbId, int chunkIndex) {
        Chunk chunk = new Chunk();
        chunk.setId(UUID.randomUUID().toString());
        chunk.setDocumentId(documentId);
        chunk.setContent(content);
        chunk.setChunkIndex(chunkIndex);
        chunk.setTokenCount(content.length() / 4);
        chunk.getMetadata().put("kbId", kbId);
        chunk.getMetadata().put("chunkStrategy", "fixed");
        return chunk;
    }
}
