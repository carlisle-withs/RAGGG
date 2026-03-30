package com.rag.application.chat;

import com.rag.infrastructure.llm.ChatModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询改写服务
 *
 * 使用 LLM 对用户查询进行改写、扩展和分解:
 * - expand(): 同义词扩展、语义扩展
 * - decompose(): 复杂问题拆分为多个子问题
 */
@Service
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);

    private final ChatModelService chatModel;

    public QueryRewriter(ChatModelService chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 查询扩展
     *
     * 对原始查询进行同义词扩展和语义优化，
     * 以提高检索的召回率。
     *
     * @param query 原始查询
     * @return 扩展后的查询
     */
    public String expand(String query) {
        log.info("=== Query Expansion ===");
        log.info("Original query: {}", query);

        try {
            String prompt = buildExpandPrompt(query);
            String expanded = chatModel.generate(prompt).trim();

            log.info("Expanded query: {}", expanded);
            return expanded;

        } catch (Exception e) {
            log.error("Query expansion failed", e);
            return query; // 失败时返回原始查询
        }
    }

    /**
     * 查询分解
     *
     * 将复杂问题拆分为多个简单的子问题，
     * 每个子问题可以独立检索。
     *
     * @param query 复杂查询
     * @return 子问题列表
     */
    public List<String> decompose(String query) {
        log.info("=== Query Decomposition ===");
        log.info("Original query: {}", query);

        try {
            String prompt = buildDecomposePrompt(query);
            String response = chatModel.generate(prompt).trim();

            log.info("LLM Response: {}", response);
            return parseDecomposedQueries(response, query);

        } catch (Exception e) {
            log.error("Query decomposition failed", e);
            // 失败时返回包含原始查询的列表
            return List.of(query);
        }
    }

    /**
     * HyDE 模式: 生成假设性答案
     *
     * 使用 LLM 生成 k 个假设性的答案文档，
     * 然后对这些假设文档进行检索。
     * 这种方法可以捕捉查询的隐含意图。
     *
     * @param query 原始查询
     * @param count 生成假设答案的数量
     * @return 假设性答案列表
     */
    public List<String> generateHypotheticalDocuments(String query, int count) {
        log.info("=== HyDE - Generate Hypothetical Documents ===");
        log.info("Query: {}, Count: {}", query, count);

        try {
            String prompt = buildHyDEPrompt(query, count);
            String response = chatModel.generate(prompt).trim();

            log.info("Generated {} hypothetical documents", count);
            return parseHypotheticalDocuments(response, count);

        } catch (Exception e) {
            log.error("HyDE document generation failed", e);
            return List.of();
        }
    }

    /**
     * 构建查询扩展的 Prompt
     */
    private String buildExpandPrompt(String query) {
        return """
                你是一个查询优化专家。请对以下用户查询进行扩展和优化。

                任务:
                1. 添加同义词和相关的专业术语
                2. 补充查询的上下文信息
                3. 修正可能的歧义表达
                4. 保持查询的核心语义不变

                要求:
                - 只返回一个扩展后的查询
                - 不要解释或说明，直接返回扩展后的查询文本
                - 查询应该清晰、简洁、准确

                用户查询: "%s"

                扩展后的查询:
                """.formatted(query);
    }

    /**
     * 构建查询分解的 Prompt
     */
    private String buildDecomposePrompt(String query) {
        return """
                你是一个问题分解专家。请将以下复杂问题拆分为多个简单的子问题。

                拆分原则:
                1. 每个子问题应该简洁明确
                2. 子问题之间应该相互独立
                3. 所有子问题组合起来应该能完整回答原始问题
                4. 如果问题简单直接，返回单个问题即可

                输出格式:
                返回一个 JSON 数组格式的子问题列表:
                ["子问题1", "子问题2", ...]

                用户查询: "%s"

                子问题列表 (JSON 数组格式):
                """.formatted(query);
    }

    /**
     * 构建 HyDE 的 Prompt
     */
    private String buildHyDEPrompt(String query, int count) {
        return """
                你是一个文档生成专家。请根据用户问题生成 %d 个假设性的答案文档。

                生成要求:
                1. 每个假设性答案应该是一个简短但信息丰富的段落
                2. 答案应该直接针对用户问题
                3. 多个答案应该覆盖不同的角度或解释
                4. 虽然答案可能是"假设的"，但要基于合理的知识

                输出格式:
                返回一个 JSON 数组格式的答案列表:
                ["答案1", "答案2", ...]

                用户问题: "%s"

                假设性答案列表 (JSON 数组格式):
                """.formatted(count, query);
    }

    /**
     * 解析分解后的子问题
     */
    private List<String> parseDecomposedQueries(String llmResponse, String originalQuery) {
        try {
            // 尝试提取 JSON 数组
            int arrayStart = llmResponse.indexOf('[');
            int arrayEnd = llmResponse.lastIndexOf(']');

            if (arrayStart != -1 && arrayEnd != -1) {
                String jsonArray = llmResponse.substring(arrayStart, arrayEnd + 1);
                // 简单解析：提取引号中的内容
                List<String> queries = new ArrayList<>();
                int start = 0;
                while (true) {
                    int quoteStart = jsonArray.indexOf('"', start);
                    if (quoteStart == -1) break;
                    int quoteEnd = jsonArray.indexOf('"', quoteStart + 1);
                    if (quoteEnd == -1) break;
                    String query = jsonArray.substring(quoteStart + 1, quoteEnd);
                    queries.add(query);
                    start = quoteEnd + 1;
                }
                if (!queries.isEmpty()) {
                    return queries;
                }
            }

            // 如果解析失败，检查是否有"问题"或"query"关键词
            if (llmResponse.contains("问题") || llmResponse.contains("?")) {
                List<String> queries = new ArrayList<>();
                String[] lines = llmResponse.split("[\n。]");
                for (String line : lines) {
                    line = line.trim();
                    if (line.length() > 5 && (line.contains("?") || line.matches(".*[吗呢吧呀啊].*") || line.startsWith("1") || line.startsWith("2"))) {
                        queries.add(line.replaceAll("^\\d+[.、]\\s*", ""));
                    }
                }
                if (!queries.isEmpty()) {
                    return queries;
                }
            }

        } catch (Exception e) {
            log.error("Failed to parse decomposed queries: {}", llmResponse, e);
        }

        // 默认返回原始查询
        return List.of(originalQuery);
    }

    /**
     * 解析假设性答案
     */
    private List<String> parseHypotheticalDocuments(String llmResponse, int count) {
        try {
            int arrayStart = llmResponse.indexOf('[');
            int arrayEnd = llmResponse.lastIndexOf(']');

            if (arrayStart != -1 && arrayEnd != -1) {
                String jsonArray = llmResponse.substring(arrayStart, arrayEnd + 1);
                List<String> docs = new ArrayList<>();
                int start = 0;
                while (true) {
                    int quoteStart = jsonArray.indexOf('"', start);
                    if (quoteStart == -1 || quoteStart >= arrayEnd) break;
                    int quoteEnd = jsonArray.indexOf('"', quoteStart + 1);
                    if (quoteEnd == -1 || quoteEnd > arrayEnd) break;
                    String doc = jsonArray.substring(quoteStart + 1, quoteEnd);
                    docs.add(doc);
                    start = quoteEnd + 1;
                }
                if (!docs.isEmpty()) {
                    return docs;
                }
            }

        } catch (Exception e) {
            log.error("Failed to parse hypothetical documents: {}", llmResponse, e);
        }

        return List.of();
    }
}
