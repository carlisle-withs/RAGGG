package com.rag.application.chat;

import com.rag.infrastructure.llm.ChatModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 意图分类服务
 *
 * 使用 LLM 分析用户查询的意图类型:
 * - KNOWLEDGE_QA: 知识库问答
 * - CHIT_CHAT: 闲聊
 * - PRECISE_SEARCH: 精确搜索
 * - SUMMARY: 摘要请求
 * - UNKNOWN: 未分类
 */
@Service
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    private final ChatModelService chatModel;

    public IntentClassifier(ChatModelService chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 意图类型枚举
     */
    public enum Intent {
        /**
         * 知识库问答 - 需要检索知识库回答的事实性问题
         */
        KNOWLEDGE_QA,

        /**
         * 闲聊 - 不需要检索，通用对话
         */
        CHIT_CHAT,

        /**
         * 精确搜索 - 需要从知识库精确查找信息
         */
        PRECISE_SEARCH,

        /**
         * 摘要请求 - 请求对某个主题或内容进行摘要
         */
        SUMMARY,

        /**
         * 未知/多意图 - 无法明确分类或包含多个意图
         */
        UNKNOWN
    }

    /**
     * 意图分类结果
     *
     * @param intent 识别的意图类型
     * @param confidence 置信度 (0-1)
     * @param reasoning 推理说明
     */
    public record IntentResult(Intent intent, double confidence, String reasoning) {}

    /**
     * 对用户查询进行意图分类
     *
     * @param query 用户查询文本
     * @return 意图分类结果
     */
    public IntentResult classify(String query) {
        log.info("=== Intent Classification ===");
        log.info("Query: {}", query);

        try {
            String prompt = buildIntentPrompt(query);
            String response = chatModel.generate(prompt);

            log.info("LLM Response: {}", response);

            // 解析 LLM 返回结果
            return parseIntentResult(response, query);

        } catch (Exception e) {
            log.error("Intent classification failed", e);
            // 失败时默认返回知识库问答
            return new IntentResult(Intent.KNOWLEDGE_QA, 0.5, "Classification failed, defaulting to KNOWLEDGE_QA");
        }
    }

    /**
     * 构建意图分类的 Prompt
     */
    private String buildIntentPrompt(String query) {
        return """
                你是一个意图分类器。请分析以下用户 query 的意图类型。

                意图类型定义:
                - KNOWLEDGE_QA: 知识库问答。用户提出需要从知识库或文档中查找信息来回答的问题，如"请介绍一下XXX"、"XXX是什么"、"如何实现XXX"等。
                - CHIT_CHAT: 闲聊。用户进行的一般性对话，不需要检索知识库，如"你好"、"今天天气怎么样"、"谢谢"等。
                - PRECISE_SEARCH: 精确搜索。用户明确要求查找特定信息，如"查找XXX文档"、"搜索关于XXX的内容"等。
                - SUMMARY: 摘要请求。用户请求对某个主题或内容进行总结概括。
                - UNKNOWN: 无法分类或包含多个意图。

                请返回一个 JSON 格式的结果:
                {"intent": "意图类型", "confidence": 0.0-1.0, "reasoning": "简短推理说明"}

                示例:
                - query: "RAG系统是什么？"
                  response: {"intent": "KNOWLEDGE_QA", "confidence": 0.95, "reasoning": "用户询问概念定义，属于知识库问答"}

                - query: "你好"
                  response: {"intent": "CHIT_CHAT", "confidence": 0.9, "reasoning": "通用问候语，属于闲聊"}

                用户 query: "%s"

                请返回 JSON 格式的结果（只返回 JSON，不要有其他内容）:
                """.formatted(query);
    }

    /**
     * 解析 LLM 返回的意图分类结果
     */
    private IntentResult parseIntentResult(String llmResponse, String query) {
        try {
            // 简单解析 JSON（实际生产环境应使用 JSON 解析库）
            String response = llmResponse.trim();

            // 提取 intent
            Intent intent = Intent.UNKNOWN;
            for (Intent i : Intent.values()) {
                if (response.contains(i.name())) {
                    intent = i;
                    break;
                }
            }

            // 提取 confidence
            double confidence = 0.5;
            int confStart = response.indexOf("\"confidence\"");
            if (confStart != -1) {
                int colonPos = response.indexOf(":", confStart);
                int commaPos = response.indexOf(",", confStart);
                if (colonPos != -1 && commaPos != -1) {
                    String confStr = response.substring(colonPos + 1, commaPos).trim();
                    confidence = Double.parseDouble(confStr);
                }
            }

            // 提取 reasoning
            String reasoning = "";
            int reasonStart = response.indexOf("\"reasoning\"");
            if (reasonStart != -1) {
                int colonPos = response.indexOf(":", reasonStart);
                int endQuote = response.indexOf("\"", colonPos + 2);
                if (colonPos != -1 && endQuote != -1) {
                    reasoning = response.substring(colonPos + 2, endQuote);
                }
            }

            log.info("Parsed intent: {}, confidence: {}, reasoning: {}", intent, confidence, reasoning);
            return new IntentResult(intent, confidence, reasoning);

        } catch (Exception e) {
            log.error("Failed to parse intent result: {}", llmResponse, e);
            // 默认返回知识库问答
            return new IntentResult(Intent.KNOWLEDGE_QA, 0.5, "Parse failed");
        }
    }

    /**
     * 判断是否为知识库问答意图
     */
    public boolean isKnowledgeQA(IntentResult intentResult) {
        return intentResult != null &&
               (intentResult.intent() == Intent.KNOWLEDGE_QA ||
                intentResult.intent() == Intent.PRECISE_SEARCH ||
                intentResult.intent() == Intent.UNKNOWN);
    }

    /**
     * 判断是否需要检索（知识库问答需要检索）
     */
    public boolean needsRetrieval(IntentResult intentResult) {
        return intentResult != null &&
               (intentResult.intent() == Intent.KNOWLEDGE_QA ||
                intentResult.intent() == Intent.PRECISE_SEARCH);
    }
}
