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
    private int minParagraphsToMerge = 2;

    // 预编译正则表达式，优化4: 避免重复编译
    private static final Pattern DOUBLE_NEWLINE = Pattern.compile("\n\\s*\n");
    private static final Pattern SINGLE_NEWLINE = Pattern.compile("\n");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+.+$", Pattern.MULTILINE);
    private static final Pattern CODE_BLOCK = Pattern.compile("```[\\s\\S]*?```");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*[-*+]\\s+", Pattern.MULTILINE);
    private static final Pattern NUMBERED_LIST = Pattern.compile("^\\s*\\d+\\.\\s+", Pattern.MULTILINE);

    @Override
    public List<Chunk> chunk(String text, String documentId, String kbId) {
        List<Chunk> chunks = new ArrayList<>();

        // Step 1: 识别文档结构
        List<DocumentSegment> segments = parseDocumentStructure(text);

        // Step 2: 合并小段落 + 按大小限制分组
        List<String> mergedContents = mergeAndGroup(segments);

        // Step 3: 创建Chunk并保留元数据
        String currentHeading = "";
        int offset = 0;

        for (int i = 0; i < mergedContents.size(); i++) {
            String content = mergedContents.get(i);
            Chunk chunk = createChunk(content, documentId, kbId, i, offset, currentHeading);
            chunks.add(chunk);
            offset += content.length();
        }

        return chunks;
    }

    private List<DocumentSegment> parseDocumentStructure(String text) {
        List<DocumentSegment> segments = new ArrayList<>();

        // 识别代码块（保持代码块完整不切分）
        int codeBlockStart = 0;
        int textStart = 0;

        while (textStart < text.length()) {
            // 查找下一个代码块开始位置
            int nextCodeBlock = text.indexOf("```", textStart);

            if (nextCodeBlock == -1) {
                // 没有更多代码块，处理剩余文本
                processTextContent(text.substring(textStart), segments);
                break;
            }

            // 处理代码块前的文本
            if (nextCodeBlock > textStart) {
                processTextContent(text.substring(textStart, nextCodeBlock), segments);
            }

            // 找到代码块结束位置
            int codeBlockEnd = text.indexOf("```", nextCodeBlock + 3);
            if (codeBlockEnd == -1) {
                codeBlockEnd = text.length();
            }

            // 添加代码块作为单独段落
            String codeContent = text.substring(nextCodeBlock, codeBlockEnd + 3);
            segments.add(new DocumentSegment(
                codeContent,
                SegmentType.CODE_BLOCK,
                nextCodeBlock
            ));

            textStart = codeBlockEnd + 3;
        }

        return segments;
    }

    private void processTextContent(String content, List<DocumentSegment> segments) {
        if (content.trim().isEmpty()) return;

        // 分割段落
        String[] paragraphs = DOUBLE_NEWLINE.split(content);

        for (String paragraph : paragraphs) {
            if (paragraph.trim().isEmpty()) continue;

            // 识别标题
            if (MARKDOWN_HEADING.matcher(paragraph.trim()).matches()) {
                segments.add(new DocumentSegment(
                    paragraph.trim(),
                    SegmentType.HEADING,
                    0
                ));
            }
            // 识别列表项
            else if (LIST_ITEM.matcher(paragraph.trim()).matches() ||
                     NUMBERED_LIST.matcher(paragraph.trim()).matches()) {
                segments.add(new DocumentSegment(
                    paragraph.trim(),
                    SegmentType.LIST_ITEM,
                    0
                ));
            }
            // 普通段落
            else {
                segments.add(new DocumentSegment(
                    paragraph.trim(),
                    SegmentType.PARAGRAPH,
                    0
                ));
            }
        }
    }

    private List<String> mergeAndGroup(List<DocumentSegment> segments) {
        List<String> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        String currentHeading = "";

        for (DocumentSegment segment : segments) {
            // 跟踪当前标题
            if (segment.type == SegmentType.HEADING) {
                currentHeading = extractHeadingText(segment.content);
            }

            String content = segment.content;

            // 如果是代码块，直接添加（不与其它内容合并）
            if (segment.type == SegmentType.CODE_BLOCK) {
                if (buffer.length() > 0) {
                    String merged = buffer.toString();
                    result.addAll(splitIfNeeded(merged, currentHeading));
                    buffer = new StringBuilder();
                }
                result.add(content);
                continue;
            }

            // 优化1: 合并过短的相邻段落
            if (buffer.length() + content.length() < minParagraphLength / 2) {
                buffer.append(content).append("\n\n");
                continue;
            }

            // 如果添加这个段落会超过最大值，先flush
            if (buffer.length() + content.length() > maxParagraphLength && buffer.length() > 0) {
                result.add(buffer.toString().trim());
                buffer = new StringBuilder();
            }

            buffer.append(content).append("\n\n");

            // 达到最小长度或接近最大长度时flush
            if (buffer.length() >= minParagraphLength || buffer.length() > maxParagraphLength * 0.9) {
                result.add(buffer.toString().trim());
                buffer = new StringBuilder();
            }
        }

        // 处理剩余buffer
        if (buffer.length() > 0) {
            result.add(buffer.toString().trim());
        }

        return result;
    }

    private List<String> splitIfNeeded(String content, String heading) {
        List<String> result = new ArrayList<>();

        if (content.length() <= maxParagraphLength) {
            result.add(content);
            return result;
        }

        // 按段落分割过长的内容
        String[] paragraphs = DOUBLE_NEWLINE.split(content);
        StringBuilder buffer = new StringBuilder();

        for (String para : paragraphs) {
            if (buffer.length() + para.length() > maxParagraphLength && buffer.length() > 0) {
                result.add(buffer.toString().trim());
                buffer = new StringBuilder();
            }
            buffer.append(para).append("\n\n");
        }

        if (buffer.length() > 0) {
            result.add(buffer.toString().trim());
        }

        return result;
    }

    private String extractHeadingText(String heading) {
        // 移除 # 符号并返回标题文本
        return heading.replaceAll("^#+\\s+", "").trim();
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

    private Chunk createChunk(String content, String documentId, String kbId, int chunkIndex, int offset, String heading) {
        Chunk chunk = new Chunk();
        chunk.setId(UUID.randomUUID().toString());
        chunk.setDocumentId(documentId);
        chunk.setContent(content);
        chunk.setChunkIndex(chunkIndex);
        chunk.setTokenCount(estimateTokenCount(content));
        chunk.getMetadata().put("kbId", kbId);
        chunk.getMetadata().put("chunkStrategy", "structural");
        // 优化2: 保留结构信息元数据
        chunk.getMetadata().put("heading", heading);
        chunk.getMetadata().put("offset", String.valueOf(offset));

        // 优化3: 质量评估标记
        if (chunk.getTokenCount() < 50) {
            chunk.getMetadata().put("qualityWarning", "too_small");
        } else if (chunk.getTokenCount() > 1500) {
            chunk.getMetadata().put("qualityWarning", "too_large");
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

    // 内部类：文档段落
    private static class DocumentSegment {
        final String content;
        final SegmentType type;
        final int offset;

        DocumentSegment(String content, SegmentType type, int offset) {
            this.content = content;
            this.type = type;
            this.offset = offset;
        }
    }

    private enum SegmentType {
        PARAGRAPH,
        HEADING,
        LIST_ITEM,
        CODE_BLOCK
    }
}
