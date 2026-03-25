package com.rag.domain.chunking;

import com.rag.domain.model.Chunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class ChunkStrategyTest {

    private static final String TEST_TEXT = loadTestText();

    @Test
    @DisplayName("测试 FixedChunkStrategy 质量评估")
    void testFixedChunkStrategyQualityAssessment() {
        ChunkStrategyFactory factory = new ChunkStrategyFactory();
        Map<String, Object> params = new HashMap<>();
        params.put("chunkSize", 512);
        params.put("chunkOverlap", 50);

        ChunkStrategy strategy = factory.getStrategy("fixed", params);
        List<Chunk> chunks = strategy.chunk(TEST_TEXT, "doc-001", "kb-001");

        System.out.println("\n========== FixedChunkStrategy 测试结果 ==========");
        System.out.println("分块数量: " + chunks.size());

        boolean hasSmallChunk = false;
        boolean hasLargeChunk = false;

        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            String warning = chunk.getMetadata().get("qualityWarning");
            String offset = chunk.getMetadata().get("offset");
            String endOffset = chunk.getMetadata().get("endOffset");

            System.out.println("\nChunk[" + i + "]:");
            System.out.println("  Token数: " + chunk.getTokenCount());
            System.out.println("  内容长度: " + chunk.getContent().length() + " 字符");
            System.out.println("  原文位置: " + offset + " -> " + endOffset);
            System.out.println("  质量警告: " + (warning != null ? warning : "无"));

            if ("too_small".equals(warning)) hasSmallChunk = true;
            if ("too_large".equals(warning)) hasLargeChunk = true;
        }

        // 验证质量评估机制工作正常
        assertFalse(chunks.isEmpty(), "应该产生分块");
        System.out.println("\n存在过小Chunk: " + hasSmallChunk);
        System.out.println("存在过大Chunk: " + hasLargeChunk);
    }

    @Test
    @DisplayName("测试 StructuralChunkStrategy 质量评估")
    void testStructuralChunkStrategyQualityAssessment() {
        ChunkStrategyFactory factory = new ChunkStrategyFactory();
        Map<String, Object> params = new HashMap<>();
        params.put("minParagraphLength", 300);
        params.put("maxParagraphLength", 1000);

        ChunkStrategy strategy = factory.getStrategy("structural", params);
        List<Chunk> chunks = strategy.chunk(TEST_TEXT, "doc-001", "kb-001");

        System.out.println("\n========== StructuralChunkStrategy 测试结果 ==========");
        System.out.println("分块数量: " + chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            String heading = chunk.getMetadata().get("heading");
            String warning = chunk.getMetadata().get("qualityWarning");
            String cstrategy = chunk.getMetadata().get("chunkStrategy");

            System.out.println("\nChunk[" + i + "]:");
            System.out.println("  所属标题: " + (heading != null && !heading.isEmpty() ? heading : "无"));
            System.out.println("  Token数: " + chunk.getTokenCount());
            System.out.println("  内容预览: " + truncate(chunk.getContent(), 50) + "...");
            System.out.println("  质量警告: " + (warning != null ? warning : "无"));
            System.out.println("  分块策略: " + cstrategy);
        }

        assertFalse(chunks.isEmpty(), "应该产生分块");
    }

    @Test
    @DisplayName("测试 SemanticChunkStrategy 质量评估")
    void testSemanticChunkStrategyQualityAssessment() {
        ChunkStrategyFactory factory = new ChunkStrategyFactory();
        Map<String, Object> params = new HashMap<>();
        params.put("maxTokensPerChunk", 200);

        ChunkStrategy strategy = factory.getStrategy("semantic", params);
        List<Chunk> chunks = strategy.chunk(TEST_TEXT, "doc-001", "kb-001");

        System.out.println("\n========== SemanticChunkStrategy 测试结果 ==========");
        System.out.println("分块数量: " + chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            String offset = chunk.getMetadata().get("offset");
            String endOffset = chunk.getMetadata().get("endOffset");
            String sentenceCount = chunk.getMetadata().get("sentenceCount");
            String warning = chunk.getMetadata().get("qualityWarning");

            System.out.println("\nChunk[" + i + "]:");
            System.out.println("  Token数: " + chunk.getTokenCount());
            System.out.println("  句子数: " + (sentenceCount != null ? sentenceCount : "未知"));
            System.out.println("  原文位置: " + offset + " -> " + endOffset);
            System.out.println("  内容预览: " + truncate(chunk.getContent(), 50) + "...");
            System.out.println("  质量警告: " + (warning != null ? warning : "无"));
        }

        assertFalse(chunks.isEmpty(), "应该产生分块");
    }

    @Test
    @DisplayName("测试 HybridChunkStrategy 质量评估")
    void testHybridChunkStrategyQualityAssessment() {
        ChunkStrategyFactory factory = new ChunkStrategyFactory();
        Map<String, Object> params = new HashMap<>();
        params.put("maxTokensPerChunk", 100);

        ChunkStrategy strategy = factory.getStrategy("hybrid", params);
        List<Chunk> chunks = strategy.chunk(TEST_TEXT, "doc-001", "kb-001");

        System.out.println("\n========== HybridChunkStrategy 测试结果 ==========");
        System.out.println("分块数量: " + chunks.size());

        // 统计质量警告
        Map<String, Long> warningStats = chunks.stream()
            .map(c -> c.getMetadata().get("qualityWarning"))
            .filter(w -> w != null)
            .collect(Collectors.groupingBy(w -> w, Collectors.counting()));

        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            String warning = chunk.getMetadata().get("qualityWarning");
            String offset = chunk.getMetadata().get("offset");

            System.out.println("\nChunk[" + i + "]:");
            System.out.println("  Token数: " + chunk.getTokenCount());
            System.out.println("  原文位置: " + offset);
            System.out.println("  内容预览: " + truncate(chunk.getContent(), 60) + "...");
            System.out.println("  质量警告: " + (warning != null ? warning : "无"));
        }

        System.out.println("\n========== 质量警告统计 ==========");
        warningStats.forEach((warning, count) ->
            System.out.println("  " + warning + ": " + count + " 个"));

        assertFalse(chunks.isEmpty(), "应该产生分块");
    }

    @Test
    @DisplayName("对比四种策略的分块效果")
    void testAllStrategiesComparison() {
        ChunkStrategyFactory factory = new ChunkStrategyFactory();

        System.out.println("\n========== 四种策略对比测试 ==========");
        System.out.println("测试文本长度: " + TEST_TEXT.length() + " 字符");
        System.out.println("测试文本预览: " + truncate(TEST_TEXT, 100) + "...\n");

        String[] strategyNames = {"fixed", "structural", "semantic", "hybrid"};
        Map<String, Object> params = new HashMap<>();
        params.put("maxTokensPerChunk", 200);
        params.put("minTokensPerChunk", 30);
        params.put("chunkSize", 350);
        params.put("chunkOverlap", 40);
        params.put("minParagraphLength", 150);
        params.put("maxParagraphLength", 2500);

        for (String name : strategyNames) {
            ChunkStrategy strategy = factory.getStrategy(name, params);
            List<Chunk> chunks = strategy.chunk(TEST_TEXT, "doc-001", "kb-001");

            // 统计
            int totalTokens = chunks.stream().mapToInt(Chunk::getTokenCount).sum();
            int avgTokens = chunks.isEmpty() ? 0 : totalTokens / chunks.size();
            int minTokens = chunks.stream().mapToInt(Chunk::getTokenCount).min().orElse(0);
            int maxTokens = chunks.stream().mapToInt(Chunk::getTokenCount).max().orElse(0);
            long warnings = chunks.stream()
                .filter(c -> c.getMetadata().containsKey("qualityWarning"))
                .count();

            System.out.println("【" + name.toUpperCase() + "】");
            System.out.println("  分块数: " + chunks.size());
            System.out.println("  Token统计: 最小=" + minTokens + ", 平均=" + avgTokens + ", 最大=" + maxTokens);
            System.out.println("  质量警告数: " + warnings);
            System.out.println();

            // 使用新的质量分析器进行深度分析
            ChunkQualityAnalyzer.AnalysisResult analysis = ChunkQualityAnalyzer.analyze(name, chunks);
            ChunkQualityAnalyzer.printReport(analysis);
        }
    }

    @Test
    @DisplayName("深度质量分析 - ChunkQualityAnalyzer 8项检测")
    void testChunkQualityAnalyzer() {
        ChunkStrategyFactory factory = new ChunkStrategyFactory();

        System.out.println("\n╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║        8维质量分析 - 全面检测分块合理性                          ║");
        System.out.println("╠════════════════════════════════════════════════════════════════╣");
        System.out.println("║  1. TOO_SMALL           - Token数过少 (<50)                    ║");
        System.out.println("║  2. TOO_LARGE           - Token数过多 (>500)                  ║");
        System.out.println("║  3. SENTENCE_CUT_OFF    - 句子被切断                           ║");
        System.out.println("║  4. PARAGRAPH_CUT_OFF   - 段落被切断                           ║");
        System.out.println("║  5. LOW_DENSITY         - 信息密度过低 (<0.3)                 ║");
        System.out.println("║  6. NO_PROPER_ENDING    - 没有适当结尾标点                     ║");
        System.out.println("║  7. HIGH_OVERLAP        - 与相邻chunk重叠率过高 (>30%)        ║");
        System.out.println("║  8. LEADING_TRAILING_REPEAT - 首尾词汇重复                     ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝\n");

        Map<String, Object> params = new HashMap<>();
        params.put("chunkSize", 350);
        params.put("chunkOverlap", 40);

        // 使用 Fixed 策略进行深度分析
        ChunkStrategy strategy = factory.getStrategy("fixed", params);
        List<Chunk> chunks = strategy.chunk(TEST_TEXT, "doc-001", "kb-001");

        ChunkQualityAnalyzer.AnalysisResult result = ChunkQualityAnalyzer.analyze("fixed", chunks);
        ChunkQualityAnalyzer.printReport(result);

        // 显示每个chunk的详细信息
        System.out.println("\n  各Chunk详细分析:");
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            int finalI = i;
            List<ChunkQualityAnalyzer.ChunkIssue> chunkIssues = result.getIssues().stream()
                .filter(issue -> issue.getChunkIndex() == finalI)
                .toList();

            System.out.println("\n  Chunk[" + i + "]: " + truncate(chunk.getContent(), 40));
            System.out.println("    Token数: " + chunk.getTokenCount() + " | 密度: " + calculateDensity(chunk.getContent()));

            if (chunkIssues.isEmpty()) {
                System.out.println("    ✅ 无问题");
            } else {
                for (ChunkQualityAnalyzer.ChunkIssue issue : chunkIssues) {
                    System.out.println("    ⚠️ " + issue.getType().name() + ": " + issue.getMessage());
                }
            }
        }

        assertFalse(chunks.isEmpty(), "应该产生分块");
    }

    private String calculateDensity(String content) {
        if (content == null || content.isEmpty()) return "0.00";
        int meaningful = 0;
        for (char c : content.toCharArray()) {
            if (Character.isLetterOrDigit(c) ||
                Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                meaningful++;
            }
        }
        return String.format("%.2f", (double) meaningful / content.length());
    }

    @Test
    @DisplayName("测试 Token 估算准确性")
    void testTokenCountEstimation() {
        String chineseText = "这是一个中文字符串";
        String englishText = "This is an English sentence";
        String mixedText = "你好 World 你好";

        ChunkStrategy strategy = new FixedChunkStrategy();

        System.out.println("\n========== Token 估算测试 ==========");
        System.out.println("中文文本: \"" + chineseText + "\"");
        System.out.println("  字符数: " + chineseText.length());
        System.out.println("  估算Token: " + (chineseText.length() * 1.5));

        System.out.println("\n英文文本: \"" + englishText + "\"");
        System.out.println("  字符数: " + englishText.length());
        System.out.println("  单词数: " + englishText.split("\\s+").length);
        System.out.println("  估算Token: " + ((englishText.length() - englishText.split("\\s+").length * 3) / 4 + englishText.split("\\s+").length));

        System.out.println("\n混合文本: \"" + mixedText + "\"");
        System.out.println("  字符数: " + mixedText.length());
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    private static String loadTestText() {
        try (InputStream inputStream = ChunkStrategyTest.class.getResourceAsStream("chunk-strategy-test.txt")) {
            assertNotNull(inputStream, "测试文本资源不存在: chunk-strategy-test.txt");
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取测试文本资源失败", e);
        }
    }

    @Test
    @DisplayName("测试智能分块策略 - 自动识别文档类型")
    void testIntelligentChunking() {
        ChunkStrategyFactory factory = new ChunkStrategyFactory();

        System.out.println("\n╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║              智能分块策略测试 - 自动文档类型识别               ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝\n");

        // 测试不同的文档类型
        String[] testCases = {
            "面试题参考回答",
            "Java代码示例",
            "JSON数据",
            "CSV表格数据"
        };

        String[] testTexts = {
            "# 标题\n\n这是一个段落。\n\n## 小节\n\n这是另一个段落。\n\n---\n\n> 引用块",
            "public class Test {\n    public void main() {\n        System.out.println(\"Hello\");\n    }\n}",
            "{\"name\": \"John\", \"age\": 30, \"city\": \"Beijing\"}",
            "Name,Age,City\nJohn,30,Beijing\nMike,25,Shanghai"
        };

        String[] fileNames = {
            "面试题.md",
            "Test.java",
            "data.json",
            "users.csv"
        };

        for (int i = 0; i < testCases.length; i++) {
            System.out.println("【测试: " + testCases[i] + "】");
            System.out.println("  文件名: " + fileNames[i]);

            List<Chunk> chunks = factory.getIntelligentChunks(testTexts[i], "doc-test-" + i, "kb-001", fileNames[i]);

            // 显示检测到的文档类型和使用的策略
            for (Chunk chunk : chunks) {
                String docType = chunk.getMetadata().get("detectedDocumentType");
                String strategy = chunk.getMetadata().get("chunkingStrategy");
                System.out.println("  Chunk[" + chunk.getChunkIndex() + "]:");
                System.out.println("    文档类型: " + docType);
                System.out.println("    分块策略: " + strategy);
                System.out.println("    Token数: " + chunk.getTokenCount());
                System.out.println("    内容预览: " + truncate(chunk.getContent(), 40));
            }
            System.out.println();
        }

        // 测试 Markdown 文档（使用实际测试文本）
        System.out.println("【测试: Markdown 文档 - 使用实际测试文本】");
        List<Chunk> markdownChunks = factory.getIntelligentChunksByFileName(TEST_TEXT, "doc-md-001", "kb-001", "面试题.md");

        ChunkQualityAnalyzer.AnalysisResult result = ChunkQualityAnalyzer.analyze("intelligent", markdownChunks);

        System.out.println("  检测到的文档类型: " + (markdownChunks.isEmpty() ? "未知" :
            markdownChunks.get(0).getMetadata().get("detectedDocumentType")));
        System.out.println("  使用的分块策略: " + (markdownChunks.isEmpty() ? "未知" :
            markdownChunks.get(0).getMetadata().get("chunkingStrategy")));
        System.out.println("  分块数: " + markdownChunks.size());
        System.out.println("  问题总数: " + result.getTotalIssueCount());

        assertFalse(markdownChunks.isEmpty(), "应该产生分块");
    }

    @Test
    @DisplayName("测试文档类型检测器")
    void testDocumentTypeDetector() {
        DocumentTypeDetector detector = new DocumentTypeDetector();

        System.out.println("\n========== 文档类型检测测试 ==========");

        // 测试各种文档类型
        testDetection(detector, "# Title\n\nParagraph", "MARKDOWN");
        testDetection(detector, "<html><body><div>Content</div></body></html>", "HTML");
        testDetection(detector, "{\"key\": \"value\"}", "JSON");
        testDetection(detector, "Name,Age\nJohn,30", "CSV");
        testDetection(detector, "public class Test { }", "CODE");
        testDetection(detector, "This is plain text without any markup.", "PLAIN_TEXT");

        // 带文件名的测试
        String mdResult = detector.detect("Some content", "document.md").name();
        System.out.println("  文件名检测 (.md): " + mdResult + " → " + (mdResult.equals("MARKDOWN") ? "✅" : "❌"));

        String javaResult = detector.detect("Some content", "Main.java").name();
        System.out.println("  文件名检测 (.java): " + javaResult + " → " + (javaResult.equals("CODE") ? "✅" : "❌"));
    }

    private void testDetection(DocumentTypeDetector detector, String text, String expectedType) {
        DocumentTypeDetector.DocumentType result = detector.detect(text, null);
        System.out.println("  " + result.name() + " → " + (result.name().equals(expectedType) ? "✅" : "❌ (期望: " + expectedType + ")"));
    }
}
