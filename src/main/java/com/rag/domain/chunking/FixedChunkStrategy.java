package com.rag.domain.chunking;

import com.rag.domain.model.Chunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class FixedChunkStrategy implements ChunkStrategy {

    private int chunkSize = 512;
    private int chunkOverlap = 50;
    private static final Pattern WORD_BOUNDARY = Pattern.compile("\\s+");

    @Override
    public List<Chunk> chunk(String text, String documentId, String kbId) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        int effectiveChunkSize = Math.max(1, chunkSize);
        int effectiveChunkOverlap = Math.max(0, Math.min(chunkOverlap, effectiveChunkSize - 1));
        int step = Math.max(1, effectiveChunkSize - effectiveChunkOverlap);
        int index = 0;
        int chunkIndex = 0;

        while (index < text.length()) {
            int end = Math.min(index + effectiveChunkSize, text.length());

            // 优化1: 按单词边界切分，避免切断单词
            int wordEnd = findWordBoundary(text, index, end);

            String chunkText = text.substring(index, wordEnd);
            if (chunkText.isEmpty()) {
                // 如果找不到单词边界，回退到原始end
                chunkText = text.substring(index, end);
                wordEnd = end;
            }

            Chunk chunk = createChunk(chunkText, documentId, kbId, chunkIndex, index, wordEnd);
            chunks.add(chunk);

            if (wordEnd >= text.length()) {
                break;
            }

            index += step;
            chunkIndex++;
        }

        return chunks;
    }

    /**
     * 优化2: 智能重叠 - 尝试在句子边界开始下一个chunk
     */
    private int findSmartOverlapStart(String text, int overlapStart) {
        int searchEnd = Math.min(overlapStart + 100, text.length());

        // 查找最近的句子结束标点
        for (int i = searchEnd - 1; i > overlapStart; i--) {
            char c = text.charAt(i);
            if (isSentenceDelimiter(c) && i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1))) {
                return i + 2; // 返回标点后的第一个字符位置
            }
        }

        // 否则查找段落边界
        for (int i = searchEnd - 1; i > overlapStart; i--) {
            if (text.charAt(i) == '\n' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                return i + 2;
            }
        }

        return overlapStart;
    }

    private boolean isSentenceDelimiter(char c) {
        return c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?';
    }

    /**
     * 查找最近的单词边界
     */
    private int findWordBoundary(String text, int start, int end) {
        if (end >= text.length()) {
            return end;
        }

        // 如果end位置是空格，直接返回
        if (Character.isWhitespace(text.charAt(end - 1))) {
            return end;
        }

        // 向后查找最近的空格
        for (int i = end; i < text.length() && i < end + 50; i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }

        // 向前查找最近的空格
        for (int i = end - 1; i > start && i > end - 20; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }

        return end;
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

    private Chunk createChunk(String content, String documentId, String kbId, int chunkIndex, int offset, int endOffset) {
        Chunk chunk = new Chunk();
        chunk.setId(UUID.randomUUID().toString());
        chunk.setDocumentId(documentId);
        chunk.setContent(content);
        chunk.setChunkIndex(chunkIndex);
        chunk.setTokenCount(estimateTokenCount(content));
        chunk.getMetadata().put("kbId", kbId);
        chunk.getMetadata().put("chunkStrategy", "fixed");
        // 优化3: 保留位置信息
        chunk.getMetadata().put("offset", String.valueOf(offset));
        chunk.getMetadata().put("endOffset", String.valueOf(endOffset));
        return chunk;
    }

    /**
     * 优化4: 更准确的token估算
     * 中文约1token=1字符，英文约1token=4字符
     */
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

        // 中文字符按1token算，英文按4字符=1token算
        return chineseChars + (englishChars + 3) / 4;
    }
}
