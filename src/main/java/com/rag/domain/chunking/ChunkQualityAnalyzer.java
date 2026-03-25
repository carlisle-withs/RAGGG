package com.rag.domain.chunking;

import com.rag.domain.model.Chunk;

import java.util.*;

/**
 * 分块质量分析器
 * 提供多维度分块合理性检测
 */
public class ChunkQualityAnalyzer {

    private static final String[] CONNECTORS = {
        "但是", "然而", "不过", "而且", "并且", "同时", "此外", "另外", "因此", "所以"
    };

    private static final char[] CHINESE_PUNCTUATIONS = {
        '。', '，', '！', '？', '、', '；', '：', '"', '“', '\'', '‘',
        '(', ')', '【', '】', '《', '》'
    };

    private static final char[] SENTENCE_END_PUNCTUATIONS = {
        '。', '！', '？', '.', '!', '?'
    };

    private static final char[] MIDDLE_PUNCTUATIONS = {
        ',', '、', ';', ':'
    };

    private static final char[] PROPER_END_PUNCTUATIONS = {
        '。', '！', '？', '.', '!', '?', '"', '”', ')', '】', '》'
    };

    /**
     * 分析结果
     */
    public static class AnalysisResult {
        private final String strategy;
        private final int chunkCount;
        private final Map<String, Integer> issueCounts;
        private final List<ChunkIssue> issues;

        public AnalysisResult(String strategy, List<Chunk> chunks) {
            this.strategy = strategy;
            this.chunkCount = chunks.size();
            this.issueCounts = new HashMap<>();
            this.issues = new ArrayList<>();
            analyze(chunks);
        }

        private void analyze(List<Chunk> chunks) {
            for (int i = 0; i < chunks.size(); i++) {
                Chunk chunk = chunks.get(i);
                String content = chunk.getContent();

                // 1. 过小检测
                if (chunk.getTokenCount() < 50) {
                    addIssue(i, IssueType.TOO_SMALL, "Token数过少: " + chunk.getTokenCount());
                }

                // 2. 过大检测
                if (chunk.getTokenCount() > 500) {
                    addIssue(i, IssueType.TOO_LARGE, "Token数过多: " + chunk.getTokenCount());
                }

                // 3. 句子切断检测
                if (isSentenceCutOff(content)) {
                    addIssue(i, IssueType.SENTENCE_CUT_OFF, "句子在chunk边界被切断");
                }

                // 4. 段落切断检测
                if (isParagraphCutOff(content)) {
                    addIssue(i, IssueType.PARAGRAPH_CUT_OFF, "段落在chunk边界被切断");
                }

                // 5. 信息密度检测
                double density = calculateInformationDensity(content);
                if (density < 0.3) {
                    addIssue(i, IssueType.LOW_DENSITY, "信息密度过低: " + String.format("%.2f", density));
                }

                // 6. 标点符号检测
                if (!hasProperEnding(content)) {
                    addIssue(i, IssueType.NO_PROPER_ENDING, "Chunk没有以适当标点结尾");
                }

                // 7. 相邻chunk重复检测
                if (i > 0) {
                    Chunk prev = chunks.get(i - 1);
                    double similarity = calculateOverlapRatio(prev.getContent(), content);
                    if (similarity > 0.3) {
                        addIssue(i, IssueType.HIGH_OVERLAP,
                            "与前一个chunk重叠率过高: " + String.format("%.1f%%", similarity * 100));
                    }
                }

                // 8. 首尾词重复检测
                if (i > 0) {
                    Chunk prev = chunks.get(i - 1);
                    if (hasLeadingTrailingRepeat(prev.getContent(), content)) {
                        addIssue(i, IssueType.LEADING_TRAILING_REPEAT,
                            "Chunk首尾存在重复词汇，可能分割位置不佳");
                    }
                }
            }
        }

        private void addIssue(int chunkIndex, IssueType type, String message) {
            issues.add(new ChunkIssue(chunkIndex, type, message));
            issueCounts.merge(type.name(), 1, Integer::sum);
        }

        // Getters
        public String getStrategy() { return strategy; }
        public int getChunkCount() { return chunkCount; }
        public Map<String, Integer> getIssueCounts() { return issueCounts; }
        public List<ChunkIssue> getIssues() { return issues; }
        public int getTotalIssueCount() { return issues.size(); }
    }

    public static class ChunkIssue {
        private final int chunkIndex;
        private final IssueType type;
        private final String message;

        public ChunkIssue(int chunkIndex, IssueType type, String message) {
            this.chunkIndex = chunkIndex;
            this.type = type;
            this.message = message;
        }

        public int getChunkIndex() { return chunkIndex; }
        public IssueType getType() { return type; }
        public String getMessage() { return message; }

        @Override
        public String toString() {
            return String.format("Chunk[%d] - %s: %s", chunkIndex, type.name(), message);
        }
    }

    public enum IssueType {
        TOO_SMALL,
        TOO_LARGE,
        SENTENCE_CUT_OFF,
        PARAGRAPH_CUT_OFF,
        LOW_DENSITY,
        NO_PROPER_ENDING,
        HIGH_OVERLAP,
        LEADING_TRAILING_REPEAT
    }

    private static boolean isSentenceCutOff(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }

        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return false;
        }

        char first = trimmed.charAt(0);
        char last = trimmed.charAt(trimmed.length() - 1);

        if (Character.isLowerCase(first)) {
            if (!startsWithConnector(trimmed)) {
                return true;
            }
        }

        for (char p : MIDDLE_PUNCTUATIONS) {
            if (last == p) {
                return true;
            }
        }

        return false;
    }

    private static boolean startsWithConnector(String content) {
        for (String conn : CONNECTORS) {
            if (content.startsWith(conn)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isParagraphCutOff(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }

        String trimmed = content.trim();
        if (trimmed.startsWith("\n") || trimmed.startsWith("  ") || trimmed.startsWith("\t")) {
            return true;
        }

        String[] lines = trimmed.split("\n");
        int linesWithoutPunctuation = 0;
        for (String line : lines) {
            if (!line.trim().isEmpty() && !endsWithPunctuation(line)) {
                linesWithoutPunctuation++;
            }
        }

        return linesWithoutPunctuation > lines.length * 0.5;
    }

    private static boolean endsWithPunctuation(String line) {
        if (line.isEmpty()) {
            return false;
        }
        char last = line.charAt(line.length() - 1);
        for (char p : SENTENCE_END_PUNCTUATIONS) {
            if (last == p) {
                return true;
            }
        }
        return false;
    }

    private static double calculateInformationDensity(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }

        int totalChars = content.length();
        int meaningfulChars = 0;

        for (char c : content.toCharArray()) {
            if (Character.isLetterOrDigit(c) ||
                Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                isChinesePunctuation(c)) {
                meaningfulChars++;
            }
        }

        return (double) meaningfulChars / totalChars;
    }

    private static boolean isChinesePunctuation(char c) {
        for (char p : CHINESE_PUNCTUATIONS) {
            if (c == p) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasProperEnding(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }

        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return false;
        }

        char last = trimmed.charAt(trimmed.length() - 1);
        for (char p : PROPER_END_PUNCTUATIONS) {
            if (last == p) {
                return true;
            }
        }
        return false;
    }

    private static double calculateOverlapRatio(String content1, String content2) {
        if (content1 == null || content2 == null) {
            return 0;
        }
        if (content1.isEmpty() || content2.isEmpty()) {
            return 0;
        }

        Set<String> words1 = new HashSet<>(Arrays.asList(content1.split("\\s+")));
        Set<String> words2 = new HashSet<>(Arrays.asList(content2.split("\\s+")));

        words1.removeIf(w -> w.length() < 2);
        words2.removeIf(w -> w.length() < 2);

        if (words1.isEmpty() || words2.isEmpty()) {
            return 0;
        }

        Set<String> intersection = new HashSet<>(words1);
        intersection.retainAll(words2);

        return (double) intersection.size() / Math.min(words1.size(), words2.size());
    }

    private static boolean hasLeadingTrailingRepeat(String content1, String content2) {
        if (content1 == null || content2 == null) {
            return false;
        }

        String lastWords1 = getLastWords(content1, 3);
        String firstWords2 = getFirstWords(content2, 3);

        return lastWords1.equals(firstWords2) && !lastWords1.isEmpty();
    }

    private static String getLastWords(String content, int count) {
        String[] words = content.split("\\s+");
        if (words.length < count) {
            return String.join(" ", words);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = words.length - count; i < words.length; i++) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(words[i]);
        }
        return sb.toString();
    }

    private static String getFirstWords(String content, int count) {
        String[] words = content.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(count, words.length); i++) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(words[i]);
        }
        return sb.toString();
    }

    public static AnalysisResult analyze(String strategy, List<Chunk> chunks) {
        return new AnalysisResult(strategy, chunks);
    }

    public static void printReport(AnalysisResult result) {
        String line = "======================================================================";
        System.out.println("\n" + line);
        System.out.println("  分块质量分析报告 - " + result.getStrategy().toUpperCase() + " 策略");
        System.out.println(line);
        System.out.println("  总分块数: " + result.getChunkCount());
        System.out.println("  问题总数: " + result.getTotalIssueCount());

        if (result.getIssueCounts().isEmpty()) {
            System.out.println("  [OK] 未发现明显问题！");
        } else {
            System.out.println("\n  问题统计:");
            for (Map.Entry<String, Integer> entry : result.getIssueCounts().entrySet()) {
                System.out.println("    " + entry.getKey() + ": " + entry.getValue() + " 个");
            }

            System.out.println("\n  详细问题列表:");
            for (ChunkIssue issue : result.getIssues()) {
                System.out.println("    [WARN] " + issue);
            }
        }
    }
}
