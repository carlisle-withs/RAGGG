package com.rag.domain.chunking;

import com.rag.domain.model.Chunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Hierarchical Chunk Strategy (Sentence Window + Auto-Merging)
 *
 * 构建两级 Chunk 结构：
 * - Parent Chunks: 段落级大 Chunk，保留文档结构完整性
 * - Leaf Chunks: 句子级细粒度 Chunk，用于精确检索
 *
 * 每个 Leaf Chunk 的 metadata 中存储：
 * - parentChunkId: 指向父节点
 * - windowContent: Sentence Window 扩展内容（周围 N 句）
 * - position: 在父节点中的位置
 *
 * 检索时：细粒度召回 → Auto-Merging → Sentence Window 扩展
 */
public class HierarchicalChunkStrategy implements ChunkStrategy {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalChunkStrategy.class);

    private int leafChunkSize = 128;       // 叶子节点目标 token 数
    private int leafChunkOverlap = 20;    // 叶子节点 overlap
    private int parentChunkSize = 512;    // 父节点目标 token 数
    private int windowSize = 3;           // Sentence Window：前后各 N 句
    private int minLeafSentences = 2;     // 最小叶子句子数（防止过短）

    private static final String[] SENTENCE_DELIMITERS =
        {"。", "！", "？", "；", ".\n", "!\n", "?\n"};

    @Override
    public List<Chunk> chunk(String text, String documentId, String kbId) {
        List<Chunk> allChunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return allChunks;
        }

        // Step 1: 句子分割
        List<SentenceUnit> sentences = splitIntoSentences(text);

        // Step 2: 构建 Parent Chunks（段落级）
        List<ParentChunk> parentChunks = buildParentChunks(sentences);

        // Step 3: 构建 Leaf Chunks（句子级）+ 建立父子关系
        Map<String, String> parentIdMap = new HashMap<>();
        int globalLeafIndex = 0;

        for (ParentChunk parent : parentChunks) {
            // 添加 Parent Chunk
            Chunk parentChunk = createParentChunk(parent, documentId, kbId, allChunks.size());
            allChunks.add(parentChunk);
            String parentId = parentChunk.getId();

            // 为父节点下的每个句子创建 Leaf Chunk
            List<SentenceUnit> parentSentences = parent.sentences;
            for (int i = 0; i < parentSentences.size(); i++) {
                SentenceUnit sentence = parentSentences.get(i);

                // 构建周围窗口（前后各 windowSize 句）
                int windowStart = Math.max(0, i - windowSize);
                int windowEnd = Math.min(parentSentences.size(), i + windowSize + 1);
                String windowContent = buildWindowContent(parentSentences, windowStart, windowEnd);

                Chunk leafChunk = createLeafChunk(
                    sentence, documentId, kbId, globalLeafIndex++,
                    parentId, i, parentSentences.size(),
                    windowContent, windowStart, windowEnd, parentSentences.size()
                );
                allChunks.add(leafChunk);
                parentIdMap.put(leafChunk.getId(), parentId);
            }
        }

        log.info("[Hierarchical] Built {} parent chunks, {} leaf chunks",
            parentChunks.size(), parentIdMap.size());

        return allChunks;
    }

    /**
     * 按句子结束符分割文本
     */
    private List<SentenceUnit> splitIntoSentences(String text) {
        List<SentenceUnit> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int sentenceStart = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            // 检查句子结束
            boolean ended = false;
            for (String delim : SENTENCE_DELIMITERS) {
                if (text.regionMatches(i, delim, 0, delim.length())) {
                    current.append(c);
                    sentences.add(new SentenceUnit(
                        current.toString().trim(),
                        sentenceStart,
                        i + delim.length()
                    ));
                    current = new StringBuilder();
                    sentenceStart = i + delim.length();
                    ended = true;
                    break;
                }
            }

            if (!ended) {
                current.append(c);
            }
        }

        // 处理最后一句（无结束符）
        if (current.length() > 0 && current.toString().trim().length() > 0) {
            sentences.add(new SentenceUnit(
                current.toString().trim(),
                sentenceStart,
                text.length()
            ));
        }

        return sentences;
    }

    /**
     * 将句子按段落/标题聚合为 Parent Chunk
     * 策略：按段落分隔符（\n\n）划分段落，段落内按 token 数合并
     */
    private List<ParentChunk> buildParentChunks(List<SentenceUnit> sentences) {
        List<ParentChunk> parentChunks = new ArrayList<>();
        List<SentenceUnit> currentGroup = new ArrayList<>();
        int currentTokens = 0;
        int groupStart = 0;

        for (SentenceUnit sentence : sentences) {
            int sentenceTokens = estimateTokenCount(sentence.text);

            // 如果单句超过 parentChunkSize，强制截断
            if (sentenceTokens > parentChunkSize) {
                if (!currentGroup.isEmpty()) {
                    parentChunks.add(new ParentChunk(new ArrayList<>(currentGroup), groupStart));
                    currentGroup.clear();
                    currentTokens = 0;
                }
                // 将超长句子拆分为多个 chunk
                List<SentenceUnit> splitSentences = splitLongSentence(sentence, parentChunkSize);
                for (SentenceUnit s : splitSentences) {
                    parentChunks.add(new ParentChunk(List.of(s), s.start));
                }
                continue;
            }

            // 如果超过阈值，先保存当前组
            if (currentTokens + sentenceTokens > parentChunkSize && !currentGroup.isEmpty()) {
                parentChunks.add(new ParentChunk(new ArrayList<>(currentGroup), groupStart));
                currentGroup.clear();
                currentTokens = 0;
                groupStart = sentence.start;
            }

            currentGroup.add(sentence);
            currentTokens += sentenceTokens;
        }

        // 处理最后一个 group
        if (!currentGroup.isEmpty()) {
            parentChunks.add(new ParentChunk(new ArrayList<>(currentGroup), groupStart));
        }

        return parentChunks;
    }

    /**
     * 将超长句子拆分为多个 chunk
     */
    private List<SentenceUnit> splitLongSentence(SentenceUnit sentence, int maxTokens) {
        List<SentenceUnit> parts = new ArrayList<>();
        String text = sentence.text;
        int currentPos = 0;

        while (currentPos < text.length()) {
            int endPos = Math.min(currentPos + maxTokens * 2, text.length());
            // 尽量在标点处截断
            for (int i = endPos - 1; i >= currentPos + maxTokens; i--) {
                if (isSentenceDelimiter(text.charAt(i))) {
                    endPos = i + 1;
                    break;
                }
            }
            parts.add(new SentenceUnit(
                text.substring(currentPos, endPos),
                sentence.start + currentPos,
                sentence.start + endPos
            ));
            currentPos = endPos;
        }

        return parts;
    }

    private boolean isSentenceDelimiter(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；' || c == ',' || c == '，';
    }

    /**
     * 构建 Sentence Window 内容
     */
    private String buildWindowContent(List<SentenceUnit> sentences, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i > start) sb.append(" ");
            sb.append(sentences.get(i).text);
        }
        return sb.toString();
    }

    private Chunk createParentChunk(ParentChunk parent, String documentId, String kbId, int index) {
        StringBuilder content = new StringBuilder();
        for (SentenceUnit s : parent.sentences) {
            if (content.length() > 0) content.append(" ");
            content.append(s.text);
        }

        Chunk chunk = new Chunk();
        chunk.setId(UUID.randomUUID().toString());
        chunk.setDocumentId(documentId);
        chunk.setKbId(kbId);
        chunk.setContent(content.toString());
        chunk.setChunkIndex(index);
        chunk.setTokenCount(estimateTokenCount(content.toString()));
        chunk.getMetadata().put("kbId", kbId);
        chunk.getMetadata().put("chunkStrategy", "swa");
        chunk.getMetadata().put("chunkLevel", "parent");
        chunk.getMetadata().put("leafCount", String.valueOf(parent.sentences.size()));

        int startOffset = parent.sentences.isEmpty() ? 0 : parent.sentences.get(0).start;
        int endOffset = parent.sentences.isEmpty() ? 0 : parent.sentences.get(parent.sentences.size() - 1).end;
        chunk.getMetadata().put("offset", String.valueOf(startOffset));
        chunk.getMetadata().put("endOffset", String.valueOf(endOffset));

        return chunk;
    }

    private Chunk createLeafChunk(
            SentenceUnit sentence, String documentId, String kbId,
            int leafIndex, String parentId, int positionInParent,
            int totalSiblings, String windowContent,
            int windowStart, int windowEnd, int parentSentenceCount) {

        Chunk chunk = new Chunk();
        chunk.setId(UUID.randomUUID().toString());
        chunk.setDocumentId(documentId);
        chunk.setKbId(kbId);
        chunk.setContent(sentence.text);  // 原始句子内容
        chunk.setChunkIndex(leafIndex);
        chunk.setTokenCount(estimateTokenCount(sentence.text));
        chunk.getMetadata().put("kbId", kbId);
        chunk.getMetadata().put("chunkStrategy", "swa");
        chunk.getMetadata().put("chunkLevel", "leaf");
        chunk.getMetadata().put("parentChunkId", parentId);
        chunk.getMetadata().put("position", String.valueOf(positionInParent));
        chunk.getMetadata().put("siblingCount", String.valueOf(totalSiblings));
        chunk.getMetadata().put("windowStart", String.valueOf(windowStart));
        chunk.getMetadata().put("windowEnd", String.valueOf(windowEnd));
        chunk.getMetadata().put("parentSentenceCount", String.valueOf(parentSentenceCount));
        chunk.getMetadata().put("offset", String.valueOf(sentence.start));
        chunk.getMetadata().put("endOffset", String.valueOf(sentence.end));
        // 核心：存储 Window 内容，检索时替换
        chunk.getMetadata().put("windowContent", windowContent);

        // 质量警告
        if (sentence.text.length() < 10) {
            chunk.getMetadata().put("qualityWarning", "too_small");
        }

        return chunk;
    }

    /**
     * Token 估算（与 FixedChunkStrategy 保持一致）
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

        return chineseChars + (englishChars + 3) / 4;
    }

    // ===== 配置方法 =====
    @Override
    public String getStrategyName() {
        return "swa";
    }

    public void setLeafChunkSize(int leafChunkSize) {
        this.leafChunkSize = leafChunkSize;
    }

    public void setLeafChunkOverlap(int leafChunkOverlap) {
        this.leafChunkOverlap = leafChunkOverlap;
    }

    public void setParentChunkSize(int parentChunkSize) {
        this.parentChunkSize = parentChunkSize;
    }

    public void setWindowSize(int windowSize) {
        this.windowSize = windowSize;
    }

    // ===== 内部类 =====
    private static class SentenceUnit {
        final String text;
        final int start;
        final int end;

        SentenceUnit(String text, int start, int end) {
            this.text = text;
            this.start = start;
            this.end = end;
        }
    }

    private static class ParentChunk {
        final List<SentenceUnit> sentences;
        final int startOffset;

        ParentChunk(List<SentenceUnit> sentences, int startOffset) {
            this.sentences = sentences;
            this.startOffset = startOffset;
        }
    }
}
