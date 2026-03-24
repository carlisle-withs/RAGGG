package com.rag.domain.chunking;

import com.rag.domain.model.Chunk;
import java.util.List;

public interface ChunkStrategy {
    List<Chunk> chunk(String text, String documentId, String kbId);
    String getStrategyName();
}
