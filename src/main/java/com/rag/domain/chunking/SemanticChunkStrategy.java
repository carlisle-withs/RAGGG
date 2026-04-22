package com.rag.domain.chunking;

import com.rag.domain.model.Chunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class SemanticChunkStrategy implements ChunkStrategy {

    private int maxTokensPerChunk = 512;
    private int minTokensPerChunk = 30;
    private int minSentenceLength = 10;
    private int maxSentenceLength = 500;
    private int overlapTokens = 200; // overlap 默认 200 tokens（约 40%），可配置

    // 优化4: 预编译正则表达式
    private static final Pattern CHINESE_PUNCTUATION = Pattern.compile("[。！？；]");
    private static final Pattern CONNECTING_WORDS = Pattern.compile(
        "^(但是|然而|不过|而且|并且|同时|此外|另外|因此|所以|于是|因为|虽然|尽管)\\s*"
    );

    private static final String[] SENTENCE_DELIMITERS = {
        "。", "！", "？", "；", ".", "!", "?"
    };

    private static final String[] CHINESE_CONNECTORS = {
        "但是", "然而", "不过", "而且", "并且", "同时", "此外", "另外", "因此", "所以", "于是", "因为", "虽然", "尽管"
    };

    @Override
    public List<Chunk> chunk(String text, String documentId, String kbId) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }

        // Step 1: 智能句子切分
        List<Sentence> sentences = splitIntoSentences(text);

        // Step 2: 句子质量过滤
        List<Sentence> filteredSentences = filterSentences(sentences);

        // Step 3: 按主题语义分组
        List<List<Sentence>> groupedSentences = groupByTopic(filteredSentences);

        // Step 4: 转换为Chunks并评估质量
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < groupedSentences.size(); i++) {
            List<Sentence> group = groupedSentences.get(i);
            String content = sentencesToContent(group);
            Chunk chunk = createChunk(content, documentId, kbId, i, group);
            chunks.add(chunk);
        }

        return chunks;
    }

    private List<Sentence> splitIntoSentences(String text) {
        List<Sentence> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int sentenceStart = 0;

        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            current.append(c);

            // 检查是否是句子结束符
            boolean isDelimiter = false;
            for (String delimiter : SENTENCE_DELIMITERS) {
                if (text.regionMatches(i, delimiter, 0, delimiter.length())) {
                    isDelimiter = true;
                    sentences.add(new Sentence(
                        current.toString(),
                        sentenceStart,
                        i + delimiter.length()
                    ));
                    current = new StringBuilder();
                    sentenceStart = i + delimiter.length();
                    break;
                }
            }

            // 优化1: 特殊处理连接词作为句子边界
            if (!isDelimiter && current.length() > 2) {
                String recentText = text.substring(Math.max(0, i - 5), i + 1);
                for (String connector : CHINESE_CONNECTORS) {
                    if (recentText.endsWith(connector) && i + 1 < text.length()) {
                        // 检查后面是否有空格或换行
                        char next = text.charAt(i + 1);
                        if (Character.isWhitespace(next) || next == '"' || next == '"') {
                            sentences.add(new Sentence(
                                current.toString(),
                                sentenceStart,
                                i + 1
                            ));
                            current = new StringBuilder();
                            sentenceStart = i + 1;
                            break;
                        }
                    }
                }
            }

            i++;
        }

        // 处理最后一句
        if (current.length() > 0) {
            sentences.add(new Sentence(current.toString(), sentenceStart, text.length()));
        }

        return sentences;
    }

    private List<Sentence> filterSentences(List<Sentence> sentences) {
        List<Sentence> filtered = new ArrayList<>();

        for (Sentence sentence : sentences) {
            String content = sentence.content.trim();

            // 跳过过短或过长的句子
            if (content.length() < minSentenceLength) {
                // 尝试与下一个句子合并
                if (!filtered.isEmpty()) {
                    Sentence last = filtered.remove(filtered.size() - 1);
                    String merged = last.content + sentence.content;
                    filtered.add(new Sentence(merged, last.start, sentence.end));
                } else {
                    filtered.add(sentence);
                }
            } else if (content.length() > maxSentenceLength) {
                // 过长的句子需要分割
                filtered.addAll(splitLongSentence(sentence));
            } else {
                filtered.add(sentence);
            }
        }

        return filtered;
    }

    private static final int MAX_CHUNK_CHARS = 10000; // 每个 chunk 上限 10000 字符

    private List<Sentence> splitLongSentence(Sentence sentence) {
        List<Sentence> parts = new ArrayList<>();
        String content = sentence.content;

        // 如果本身已经够小，直接返回
        if (content.length() <= MAX_CHUNK_CHARS) {
            parts.add(sentence);
            return parts;
        }

        // 递归分割：先按逗号对半切，然后对每半继续检查
        int splitPoint = content.length() / 2;
        int commaPos = content.indexOf('，', splitPoint);
        if (commaPos > splitPoint / 2) {
            splitPoint = commaPos + 1;
        }

        Sentence left = new Sentence(content.substring(0, splitPoint), sentence.start, sentence.start + splitPoint);
        Sentence right = new Sentence(content.substring(splitPoint), sentence.start + splitPoint, sentence.end);

        // 递归处理每半，直到都小于上限
        parts.addAll(splitLongSentence(left));
        parts.addAll(splitLongSentence(right));
        return parts;
    }

    private List<List<Sentence>> groupByTopic(List<Sentence> sentences) {
        if (sentences.isEmpty()) {
            return new ArrayList<>();
        }

        // 第一轮：按 token 限制切分 chunk（无 overlap）
        List<List<Sentence>> rawChunks = new ArrayList<>();
        List<Sentence> currentGroup = new ArrayList<>();
        int currentTokens = 0;

        for (Sentence sentence : sentences) {
            int sentenceTokens = estimateTokenCount(sentence.content);

            // 单句超限，直接作为单独 chunk
            if (sentenceTokens > maxTokensPerChunk) {
                if (!currentGroup.isEmpty()) {
                    rawChunks.add(new ArrayList<>(currentGroup));
                    currentGroup.clear();
                    currentTokens = 0;
                }
                rawChunks.add(new ArrayList<>(List.of(sentence)));
                continue;
            }

            if (currentTokens + sentenceTokens > maxTokensPerChunk && !currentGroup.isEmpty()) {
                rawChunks.add(new ArrayList<>(currentGroup));
                currentGroup.clear();
                currentTokens = 0;
            }

            currentGroup.add(sentence);
            currentTokens += sentenceTokens;
        }

        if (!currentGroup.isEmpty()) {
            rawChunks.add(currentGroup);
        }

        // 第二轮：将 rawChunks 合并成带 overlap 的最终 chunks
        List<List<Sentence>> finalChunks = new ArrayList<>();

        // 计算步长 = maxTokensPerChunk - overlapTokens
        int stepTokens = Math.max(maxTokensPerChunk - overlapTokens, maxTokensPerChunk / 4);

        int i = 0;
        while (i < rawChunks.size()) {
            List<Sentence> current = new ArrayList<>(rawChunks.get(i));
            int currentSum = estimateTokenCount(sentencesToContent(current));

            // 向后尝试合并更多 chunk，直到接近 maxTokensPerChunk * 1.5
            int j = i + 1;
            while (j < rawChunks.size()) {
                List<Sentence> next = rawChunks.get(j);
                int nextSum = estimateTokenCount(sentencesToContent(next));
                if (currentSum + nextSum <= maxTokensPerChunk * 1.5) {
                    current.addAll(next);
                    currentSum = estimateTokenCount(sentencesToContent(current));
                    j++;
                } else {
                    break;
                }
            }

            // 合并太小的 group（< minTokensPerChunk）
            if (currentSum < minTokensPerChunk && finalChunks.size() > 0) {
                // 向前合并：把当前 small group 追加到上一个 chunk
                List<Sentence> prev = finalChunks.remove(finalChunks.size() - 1);
                prev.addAll(current);
                current = prev;
            }

            finalChunks.add(current);

            // 计算下一个 chunk 的起始位置（滑动步长）
            // 把当前 chunk 的最后 overlapTokens 的句子作为下一个 chunk 的开头
            int overlapCount = 0;
            int overlapSum = 0;
            List<Sentence> reversed = new ArrayList<>(current);
            java.util.Collections.reverse(reversed);
            for (Sentence s : reversed) {
                int sTokens = estimateTokenCount(s.content);
                if (overlapSum + sTokens <= overlapTokens) {
                    overlapCount++;
                    overlapSum += sTokens;
                } else {
                    break;
                }
            }

            // 跳过已覆盖的 sentences，使用滑动步长定位
            int sentencesToSkip = Math.max(1, current.size() - overlapCount);
            i += sentencesToSkip;

            // 如果步长太小导致原地踏步，强制前进一步
            if (i <= rawChunks.size() - 1 && sentencesToSkip <= 1) {
                i++;
            }
        }

        return finalChunks;
    }

    private String sentencesToContent(List<Sentence> sentences) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            sb.append(sentences.get(i).content);
            if (i < sentences.size() - 1) {
                // 在句子之间加空格（如果原文没有）
                char lastChar = sentences.get(i).content.charAt(sentences.get(i).content.length() - 1);
                if (!Character.isWhitespace(lastChar) && lastChar != '"' && lastChar != '"') {
                    sb.append(" ");
                }
            }
        }
        return sb.toString();
    }

    @Override
    public String getStrategyName() {
        return "semantic";
    }

    public void setMaxTokensPerChunk(int maxTokensPerChunk) {
        this.maxTokensPerChunk = maxTokensPerChunk;
    }

    public void setMinTokensPerChunk(int minTokensPerChunk) {
        this.minTokensPerChunk = minTokensPerChunk;
    }

    public void setOverlapTokens(int overlapTokens) {
        this.overlapTokens = overlapTokens;
    }

    private Chunk createChunk(String content, String documentId, String kbId, int chunkIndex, List<Sentence> sentences) {
        Chunk chunk = new Chunk();
        chunk.setId(UUID.randomUUID().toString());
        chunk.setDocumentId(documentId);
        chunk.setContent(content.trim());
        chunk.setChunkIndex(chunkIndex);
        chunk.setTokenCount(estimateTokenCount(content));

        // 计算原文位置
        int startOffset = sentences.isEmpty() ? 0 : sentences.get(0).start;
        int endOffset = sentences.isEmpty() ? 0 : sentences.get(sentences.size() - 1).end;

        chunk.getMetadata().put("kbId", kbId);
        chunk.getMetadata().put("chunkStrategy", "semantic");
        // 优化2: 保留位置信息和句子数
        chunk.getMetadata().put("offset", String.valueOf(startOffset));
        chunk.getMetadata().put("endOffset", String.valueOf(endOffset));
        chunk.getMetadata().put("sentenceCount", String.valueOf(sentences.size()));

        // 优化3: 质量评估
        int tokenCount = chunk.getTokenCount();
        if (tokenCount < minTokensPerChunk) {
            chunk.getMetadata().put("qualityWarning", "too_small");
        } else if (tokenCount > maxTokensPerChunk * 0.9) {
            chunk.getMetadata().put("qualityWarning", "near_limit");
        }

        return chunk;
    }

    /**
     * 优化4: 更准确的token估算
     * 使用TikToken风格的简单估算
     */
    private int estimateTokenCount(String content) {
        int chineseChars = 0;
        int englishChars = 0;
        int punctuation = 0;

        for (char c : content.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                chineseChars++;
            } else if (Character.isLetterOrDigit(c)) {
                englishChars++;
            } else if (isPunctuation(c)) {
                punctuation++;
            }
        }

        // 估算公式：
        // 中文每个字符约1.5token（含标点）
        // 英文每个单词约1.3token
        // 标点单独计算
        return (int) (chineseChars * 1.5 + englishChars / 4.0 + punctuation * 0.5);
    }

    private boolean isPunctuation(char c) {
        return SENTENCE_DELIMITERS.length > 0 || CHINESE_PUNCTUATION.matcher(String.valueOf(c)).matches();
    }

    // 内部类：句子
    private static class Sentence {
        final String content;
        final int start;
        final int end;

        Sentence(String content, int start, int end) {
            this.content = content;
            this.start = start;
            this.end = end;
        }
    }
}
