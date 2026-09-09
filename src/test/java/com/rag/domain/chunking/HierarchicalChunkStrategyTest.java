package com.rag.domain.chunking;

import com.rag.domain.model.Chunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HierarchicalChunkStrategy (SWA) 单元测试
 *
 * 测试：
 * 1. 基本分块：Parent + Leaf 两级结构
 * 2. Sentence Window：Leaf 的 metadata 中正确存储 windowContent
 * 3. 父子关系：Leaf 正确指向 Parent Chunk ID
 * 4. 分块质量：Parent token 数接近目标值
 * 5. 边界情况：空文本、短文本、超长文本
 */
public class HierarchicalChunkStrategyTest {

    private static final String TEST_TEXT = loadTestText();

    @Test
    @DisplayName("基本分块：应产生 Parent + Leaf 两级 Chunk")
    void testBasicHierarchicalChunking() {
        HierarchicalChunkStrategy strategy = new HierarchicalChunkStrategy();
        strategy.setParentChunkSize(200);
        strategy.setLeafChunkSize(50);
        strategy.setWindowSize(2);

        String text = "这是第一个句子。这是第二个句子。这是第三个句子。" +
                      "第四个句子。第五个句子。第六个句子。";

        List<Chunk> chunks = strategy.chunk(text, "doc-test-001", "kb-001");

        assertFalse(chunks.isEmpty(), "应该产生分块");
        System.out.println("\n========== 基本分层分块测试 ==========");
        System.out.println("分块数量: " + chunks.size());

        // 统计 Parent 和 Leaf
        int parentCount = 0;
        int leafCount = 0;
        for (Chunk chunk : chunks) {
            String level = chunk.getMetadata().get("chunkLevel");
            if ("parent".equals(level)) {
                parentCount++;
            } else if ("leaf".equals(level)) {
                leafCount++;
            }
        }

        System.out.println("Parent Chunks: " + parentCount);
        System.out.println("Leaf Chunks: " + leafCount);
        assertTrue(parentCount > 0, "应有至少 1 个 Parent Chunk");
        assertTrue(leafCount > 0, "应有至少 1 个 Leaf Chunk");
        assertEquals(leafCount + parentCount, chunks.size(),
                "总数应等于 Parent + Leaf");
    }

    @Test
    @DisplayName("Sentence Window：Leaf 的 windowContent 应包含周围句子")
    void testSentenceWindowMetadata() {
        HierarchicalChunkStrategy strategy = new HierarchicalChunkStrategy();
        strategy.setWindowSize(2);

        String text = "第一句。第二句。第三句。第四句。第五句。";

        List<Chunk> chunks = strategy.chunk(text, "doc-win-001", "kb-001");

        System.out.println("\n========== Sentence Window 测试 ==========");

        for (Chunk leaf : chunks) {
            if ("leaf".equals(leaf.getMetadata().get("chunkLevel"))) {
                String windowContent = leaf.getMetadata().get("windowContent");
                String originalContent = leaf.getContent();

                System.out.println("\nLeaf[" + leaf.getChunkIndex() + "]:");
                System.out.println("  原始内容: " + originalContent);
                System.out.println("  窗口内容: " +
                    (windowContent != null ? windowContent : "NULL"));
                System.out.println("  窗口大小: " +
                    (windowContent != null ? windowContent.length() : 0));

                assertNotNull(windowContent, "Leaf 应有 windowContent");
                assertTrue(windowContent.length() >= originalContent.length(),
                        "窗口内容应至少包含原始句子");
            }
        }
    }

    @Test
    @DisplayName("父子关系：Leaf 应正确指向 Parent")
    void testParentChildRelationships() {
        HierarchicalChunkStrategy strategy = new HierarchicalChunkStrategy();
        strategy.setParentChunkSize(100);

        List<Chunk> chunks = strategy.chunk(TEST_TEXT, "doc-rel-001", "kb-001");

        System.out.println("\n========== 父子关系测试 ==========");

        // 建立 parentId -> parentChunk 映射
        Map<String, Chunk> parentMap = chunks.stream()
                .filter(c -> "parent".equals(c.getMetadata().get("chunkLevel")))
                .peek(p -> System.out.println("Parent[" + p.getChunkIndex() + "]: " +
                        truncate(p.getContent(), 30) + " leafCount=" +
                        p.getMetadata().get("leafCount")))
                .peek(p -> assertNotNull(p.getId(), "Parent 应有 ID"))
                .peek(p -> assertFalse(p.getId().isEmpty(), "Parent ID 不应为空"))
                .collect(java.util.stream.Collectors.toMap(Chunk::getId, c -> c));

        // 检查 Leaf 的 parentChunkId
        for (Chunk leaf : chunks) {
            if ("leaf".equals(leaf.getMetadata().get("chunkLevel"))) {
                String parentId = leaf.getMetadata().get("parentChunkId");
                assertNotNull(parentId, "Leaf 应有 parentChunkId");
                assertTrue(parentMap.containsKey(parentId),
                        "parentChunkId 应指向存在的 Parent: " + parentId);
                assertEquals(parentMap.get(parentId).getKbId(), leaf.getKbId(),
                        "KB ID 应一致");
                assertEquals(parentMap.get(parentId).getDocumentId(), leaf.getDocumentId(),
                        "Document ID 应一致");

                // 检查 siblingCount
                String siblingCount = leaf.getMetadata().get("siblingCount");
                assertNotNull(siblingCount, "Leaf 应有 siblingCount");
                assertTrue(Integer.parseInt(siblingCount) >= 1,
                        "siblingCount 应 >= 1");
            }
        }
    }

    @Test
    @DisplayName("分块策略标记：所有 Chunk 的 metadata 应正确标记 swa")
    void testSwaMetadataTagging() {
        HierarchicalChunkStrategy strategy = new HierarchicalChunkStrategy();
        List<Chunk> chunks = strategy.chunk(TEST_TEXT, "doc-tag-001", "kb-001");

        for (Chunk chunk : chunks) {
            assertEquals("swa",
                    chunk.getMetadata().get("chunkStrategy"),
                    "chunkStrategy 应为 swa");
            assertNotNull(chunk.getMetadata().get("chunkLevel"),
                    "应有 chunkLevel 标记");
            assertNotNull(chunk.getMetadata().get("kbId"),
                    "应有 kbId");
        }
    }

    @Test
    @DisplayName("质量警告：过短句子应有 qualityWarning")
    void testQualityWarning() {
        HierarchicalChunkStrategy strategy = new HierarchicalChunkStrategy();

        String textWithShortSentences = "这是短句！这是非常长的句子用于测试分块策略的有效性。";

        List<Chunk> chunks = strategy.chunk(textWithShortSentences, "doc-qa-001", "kb-001");

        boolean hasWarning = chunks.stream()
                .anyMatch(c -> c.getMetadata().containsKey("qualityWarning"));
        System.out.println("\n========== 质量警告测试 ==========");
        System.out.println("检测到质量警告: " + hasWarning);

        // 有短句子时应标记
        assertTrue(hasWarning, "过短句子应有 qualityWarning");
    }

    @Test
    @DisplayName("Token 估算：每个 Chunk 应该有合理的 token 数")
    void testTokenCountReasonableness() {
        HierarchicalChunkStrategy strategy = new HierarchicalChunkStrategy();
        strategy.setParentChunkSize(200);

        List<Chunk> chunks = strategy.chunk(TEST_TEXT, "doc-token-001", "kb-001");

        System.out.println("\n========== Token 估算测试 ==========");

        for (Chunk chunk : chunks) {
            String level = chunk.getMetadata().get("chunkLevel");
            int tokenCount = chunk.getTokenCount();

            System.out.println("Chunk[" + chunk.getChunkIndex() + "][" + level +
                    "] tokens=" + tokenCount + " content=" +
                    truncate(chunk.getContent(), 30));

            assertTrue(tokenCount > 0, "Token 数应 > 0");

            if ("parent".equals(level)) {
                assertTrue(tokenCount <= 400,
                        "Parent token 数不应过大（期望<=200*2=" + 400 + "，实际=" + tokenCount + ")");
            }
        }
    }

    @Test
    @DisplayName("空文本和短文本边界情况")
    void testEdgeCases() {
        HierarchicalChunkStrategy strategy = new HierarchicalChunkStrategy();

        // 空文本
        List<Chunk> emptyChunks = strategy.chunk("", "doc-empty", "kb-001");
        assertTrue(emptyChunks.isEmpty(), "空文本应返回空列表");

        // 只有标点
        List<Chunk> punctuationChunks = strategy.chunk("。！！？", "doc-punct", "kb-001");
        System.out.println("\n========== 边界情况测试 ==========");
        System.out.println("标点文本分块数: " + punctuationChunks.size());
    }

    @Test
    @DisplayName("对比默认分块策略 vs SWA 分块")
    void testComparisonWithFixedStrategy() {
        HierarchicalChunkStrategy swaStrategy = new HierarchicalChunkStrategy();
        swaStrategy.setParentChunkSize(300);
        swaStrategy.setWindowSize(2);

        ChunkStrategyFactory factory = new ChunkStrategyFactory();
        ChunkStrategy fixedStrategy = factory.getStrategy("fixed",
                java.util.Map.of("chunkSize", 300, "chunkOverlap", 30));

        System.out.println("\n========== 策略对比测试 ==========");
        System.out.println("测试文本长度: " + TEST_TEXT.length() + " 字符");

        List<Chunk> swaChunks = swaStrategy.chunk(TEST_TEXT, "doc-comp-001", "kb-001");
        List<Chunk> fixedChunks = fixedStrategy.chunk(TEST_TEXT, "doc-comp-002", "kb-001");

        System.out.println("\nSWA 策略:");
        System.out.println("  分块数: " + swaChunks.size() + " (Parent + Leaf)");
        System.out.println("  Parent 数: " + swaChunks.stream()
                .filter(c -> "parent".equals(c.getMetadata().get("chunkLevel"))).count());
        System.out.println("  Leaf 数: " + swaChunks.stream()
                .filter(c -> "leaf".equals(c.getMetadata().get("chunkLevel"))).count());

        int swaParentCount = (int) swaChunks.stream()
                .filter(c -> "parent".equals(c.getMetadata().get("chunkLevel"))).count();

        System.out.println("\nFixed 策略:");
        System.out.println("  分块数: " + fixedChunks.size());

        // SWA 应该产生更少的 Parent chunk（因为聚合了）
        // 每个 Parent 包含多个句子，上下文更完整
        System.out.println("\n结论: SWA 产生了 " + swaParentCount + " 个 Parent，"
                + "每个 Parent 平均包含 " +
                (fixedChunks.isEmpty() ? 0 : (fixedChunks.size() / Math.max(1, swaParentCount)))
                + " 个句子的内容（上下文更完整）");
    }

    // ===== 测试辅助方法 =====

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    private static String loadTestText() {
        try (InputStream inputStream =
                ChunkStrategyTest.class.getResourceAsStream("chunk-strategy-test.txt")) {
            if (inputStream != null) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.err.println("无法加载测试文本，使用默认文本");
        }
        // 默认测试文本
        return "第一章：概述\n\n" +
                "机器学习是人工智能的一个分支，专注于让计算机从数据中学习。\n" +
                "深度学习是机器学习的一个子领域，使用多层神经网络。\n" +
                "自然语言处理用于处理和分析人类语言。\n\n" +
                "第二章：技术细节\n\n" +
                "Transformer 架构引入了注意力机制，大幅提升了模型性能。\n" +
                "BERT 是基于 Transformer 的预训练语言模型。\n" +
                "GPT 系列使用自回归方式进行文本生成。\n\n" +
                "第三章：应用场景\n\n" +
                "RAG 系统结合了检索和生成，提升了问答质量。\n" +
                "向量数据库用于存储和检索高维向量表示。\n" +
                "混合检索结合了关键词匹配和语义相似度。";
    }
}
