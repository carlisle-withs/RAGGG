package com.rag.domain.chunking;

import com.rag.domain.model.Chunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 混合分块策略：结合 Semantic + Fixed 的优点
 * 优先按语义分块，当段落过长时使用固定分块
 */
@Component
public class HybridChunkStrategy implements ChunkStrategy {

    private int maxTokensPerChunk = 512;
    private int minParagraphLength = 100;

    @Override
    public List<Chunk> chunk(String text, String documentId, String kbId) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        // Step 1: 先用结构化方式分割段落
        List<String> paragraphs = splitIntoParagraphs(text);

        // Step 2: 对每个段落应用混合策略
        int chunkIndex = 0;
        int offset = 0;

        for (String paragraph : paragraphs) {
            if (paragraph.trim().isEmpty()) {
                offset += paragraph.length();
                continue;
            }

            int paragraphTokens = estimateTokenCount(paragraph);

            if (paragraphTokens <= maxTokensPerChunk) {
                // 段落足够小，直接作为chunk
                chunks.add(createChunk(paragraph.trim(), documentId, kbId, chunkIndex++, offset));
            } else if (paragraphTokens <= maxTokensPerChunk * 2) {
                // 段落稍大，尝试按句子分割
                List<Chunk> semanticChunks = chunkBySentences(paragraph, documentId, kbId, chunkIndex, offset);
                chunks.addAll(semanticChunks);
                chunkIndex += semanticChunks.size();
            } else {
                // 段落过大，使用固定分块
                List<Chunk> fixedChunks = chunkByFixed(paragraph, documentId, kbId, chunkIndex, offset);
                chunks.addAll(fixedChunks);
                chunkIndex += fixedChunks.size();
            }

            offset += paragraph.length();
        }

        return chunks;
    }

    private List<String> splitIntoParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        String[] splits = text.split("\n\\s*\n");

        for (String split : splits) {
            if (!split.trim().isEmpty()) {
                paragraphs.add(split);
            }
        }

        return paragraphs;
    }

    private List<Chunk> chunkBySentences(String text, String documentId, String kbId, int startIndex, int baseOffset) {
        List<Chunk> chunks = new ArrayList<>();
        String[] sentences = text.split("(?<=[。！？！？.!?])");

        StringBuilder buffer = new StringBuilder();
        int bufferTokens = 0;
        int offset = baseOffset;
        int currentIndex = startIndex;

        for (String sentence : sentences) {
            if (sentence.trim().isEmpty()) {
                offset += sentence.length();
                continue;
            }

            int sentenceTokens = estimateTokenCount(sentence);

            if (bufferTokens + sentenceTokens > maxTokensPerChunk && buffer.length() > 0) {
                chunks.add(createChunk(buffer.toString().trim(), documentId, kbId, currentIndex++, offset - buffer.length()));
                buffer = new StringBuilder();
                bufferTokens = 0;
            }

            buffer.append(sentence);
            bufferTokens += sentenceTokens;
        }

        if (buffer.length() > 0) {
            chunks.add(createChunk(buffer.toString().trim(), documentId, kbId, currentIndex, offset - buffer.length()));
        }

        return chunks;
    }

    private List<Chunk> chunkByFixed(String text, String documentId, String kbId, int startIndex, int baseOffset) {
        List<Chunk> chunks = new ArrayList<>();
        int chunkSize = maxTokensPerChunk * 4; // 按字符估算
        int overlap = 50;
        int step = chunkSize - overlap;
        int index = 0;
        int offset = baseOffset;
        int currentIndex = startIndex;

        while (index < text.length()) {
            int end = Math.min(index + chunkSize, text.length());

            // 尝试找到句子边界
            int actualEnd = findSentenceBoundary(text, index, end);

            String content = text.substring(index, actualEnd).trim();
            if (!content.isEmpty()) {
                chunks.add(createChunk(content, documentId, kbId, currentIndex++, index + baseOffset));
            }

            if (actualEnd >= text.length()) {
                break;
            }

            index += step;
        }

        return chunks;
    }

    private int findSentenceBoundary(String text, int start, int end) {
        // 从end向前查找句子结束符
        for (int i = end - 1; i >= start && i > end - 50; i--) {
            char c = text.charAt(i);
            if (isSentenceEnd(c) && i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1))) {
                return i + 2;
            }
        }

        // 向前查找单词边界
        for (int i = end - 1; i >= start && i > end - 20; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }

        return end;
    }

    private boolean isSentenceEnd(char c) {
        return c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?';
    }

    @Override
    public String getStrategyName() {
        return "hybrid";
    }

    public void setMaxTokensPerChunk(int maxTokensPerChunk) {
        this.maxTokensPerChunk = maxTokensPerChunk;
    }

    private Chunk createChunk(String content, String documentId, String kbId, int chunkIndex, int offset) {
        Chunk chunk = new Chunk();
        chunk.setId(UUID.randomUUID().toString());
        chunk.setDocumentId(documentId);
        chunk.setContent(content);
        chunk.setChunkIndex(chunkIndex);
        chunk.setTokenCount(estimateTokenCount(content));

        chunk.getMetadata().put("kbId", kbId);
        chunk.getMetadata().put("chunkStrategy", "hybrid");
        chunk.getMetadata().put("offset", String.valueOf(offset));

        // 质量评估
        int tokenCount = chunk.getTokenCount();
        if (tokenCount < 50) {
            chunk.getMetadata().put("qualityWarning", "too_small");
        } else if (tokenCount > maxTokensPerChunk) {
            chunk.getMetadata().put("qualityWarning", "exceeded_limit");
        }

        return chunk;
    }

    private int estimateTokenCount(String content) {
        int chineseChars = 0;
        int englishChars = 0;

        for (char c : content.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                chineseChars++;
            } else if (Character.isLetterOrDigit(c)) {
                englishChars++;
            }
        }

        return chineseChars + (englishChars + 3) / 4;
    }
}
