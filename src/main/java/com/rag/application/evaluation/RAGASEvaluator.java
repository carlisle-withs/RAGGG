package com.rag.application.evaluation;

import com.rag.infrastructure.llm.ChatModelService;
import com.rag.infrastructure.llm.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAGAS 评测服务
 *
 * 实现 RAG 系统评测的各维度指标:
 * - Faithfulness (忠实度): 生成答案对检索上下文的事实一致性
 * - Answer Relevancy (回答相关性): 回答与原始问题的语义相关度
 * - Context Precision (上下文精确度): 上下文块排序质量
 * - Context Recall (上下文召回率): 检索到的信息覆盖 Ground Truth 的比例
 */
@Service
public class RAGASEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RAGASEvaluator.class);

    private final ChatModelService chatModel;
    private final EmbeddingService embeddingService;

    public RAGASEvaluator(ChatModelService chatModel, EmbeddingService embeddingService) {
        this.chatModel = chatModel;
        this.embeddingService = embeddingService;
    }

    /**
     * 评测结果
     */
    public record EvaluationResult(
            double faithfulness,
            double answerRelevancy,
            double contextPrecision,
            double contextRecall,
            double ragasScore
    ) {}

    /**
     * 评测请求
     */
    public record EvaluationRequest(
            String question,
            String groundTruth,
            String answer,
            List<String> contexts,
            String userId,
            String conversationId
    ) {}

    /**
     * 执行单条评测
     *
     * @param request 评测请求
     * @return 评测结果
     */
    public EvaluationResult evaluate(EvaluationRequest request) {
        log.info("=== RAGAS Evaluation ===");
        log.info("Question: {}", request.question());

        try {
            // 1. 计算 Faithfulness (忠实度)
            double faithfulness = calculateFaithfulness(
                    request.question(),
                    request.answer(),
                    request.contexts()
            );
            log.info("Faithfulness: {}", faithfulness);

            // 2. 计算 Answer Relevancy (回答相关性)
            double answerRelevancy = calculateAnswerRelevancy(
                    request.question(),
                    request.answer()
            );
            log.info("Answer Relevancy: {}", answerRelevancy);

            // 3. 计算 Context Precision (上下文精确度)
            double contextPrecision = calculateContextPrecision(
                    request.question(),
                    request.contexts()
            );
            log.info("Context Precision: {}", contextPrecision);

            // 4. 计算 Context Recall (上下文召回率)
            double contextRecall = calculateContextRecall(
                    request.question(),
                    request.contexts(),
                    request.groundTruth()
            );
            log.info("Context Recall: {}", contextRecall);

            // 5. 计算综合 RAGAS Score (各指标加权平均)
            double ragasScore = calculateRagasScore(faithfulness, answerRelevancy, contextPrecision, contextRecall);
            log.info("RAGAS Score: {}", ragasScore);

            return new EvaluationResult(faithfulness, answerRelevancy, contextPrecision, contextRecall, ragasScore);

        } catch (Exception e) {
            log.error("Evaluation failed", e);
            return new EvaluationResult(0.0, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /**
     * 批量评测
     */
    public List<EvaluationResult> evaluateBatch(List<EvaluationRequest> requests) {
        return requests.stream()
                .map(this::evaluate)
                .toList();
    }

    /**
     * 计算 Faithfulness (忠实度)
     *
     * 公式: faithfulness = |SU(answer) ∩ S(context)| / |SU(answer)|
     *
     * SU(answer) 是答案中的陈述集合
     * S(context) 是上下文中支持的事实集合
     */
    private double calculateFaithfulness(String question, String answer, List<String> contexts) {
        try {
            String prompt = """
                    你是一个答案评估专家。请评估以下答案对参考上下文的忠实度。

                    评估标准:
                    - 检查答案中的每个陈述是否能在上下文中找到支持
                    - 如果答案中的陈述在上下文中存在，则标记为"支持"
                    - 如果答案中的陈述在上下文中不存在，则标记为"不支持"

                    返回格式 (JSON):
                    {"faithfulness": 0.0-1.0, "supported_count": N, "total_count": M}

                    问题: %s
                    答案: %s
                    上下文:
                    %s

                    请只返回 JSON 格式的结果:
                    """.formatted(question, answer, String.join("\n", contexts));

            String response = chatModel.generate(prompt);
            return parseScore(response, "faithfulness");

        } catch (Exception e) {
            log.error("Faithfulness calculation failed", e);
            return 0.0;
        }
    }

    /**
     * 计算 Answer Relevancy (回答相关性)
     *
     * 公式: answer_relevancy = (1/n) * Σ sim(q, q_i')
     *
     * q 是原始问题
     * q_i' 是从答案中推断出的子问题
     * sim 是余弦相似度
     */
    private double calculateAnswerRelevancy(String question, String answer) {
        try {
            // 1. 从答案推断子问题
            String prompt = """
                    你是一个问题生成专家。请根据以下答案推断出 %d 个子问题，
                    这些子问题应该能够通过该答案得到完整回答。

                    返回格式 (JSON 数组):
                    ["子问题1", "子问题2", ...]

                    答案: %s

                    请只返回 JSON 数组:
                    """.formatted(3, answer);

            String response = chatModel.generate(prompt);
            List<String> inferredQuestions = parseStringList(response);

            if (inferredQuestions.isEmpty()) {
                return 0.5;
            }

            // 2. 计算原始问题与推断问题的相似度
            float[] questionEmbedding = embeddingService.embed(question);
            double totalSimilarity = 0.0;

            for (String inferredQuestion : inferredQuestions) {
                float[] inferredEmbedding = embeddingService.embed(inferredQuestion);
                double similarity = cosineSimilarity(questionEmbedding, inferredEmbedding);
                totalSimilarity += similarity;
            }

            double relevancy = totalSimilarity / inferredQuestions.size();
            return Math.min(1.0, relevancy); // 限制在 [0, 1]

        } catch (Exception e) {
            log.error("Answer relevancy calculation failed", e);
            return 0.0;
        }
    }

    /**
     * 计算 Context Precision (上下文精确度)
     *
     * 评估每个上下文块与问题的相关性
     */
    private double calculateContextPrecision(String question, List<String> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return 0.0;
        }

        try {
            int relevantCount = 0;

            for (String context : contexts) {
                String prompt = """
                        判断以下上下文片段是否与问题相关。

                        返回格式 (JSON):
                        {"relevant": true/false, "reason": "简短理由"}

                        问题: %s
                        上下文: %s

                        请只返回 JSON:
                        """.formatted(question, context);

                String response = chatModel.generate(prompt);
                if (response.contains("true") || response.contains("\"relevant\": true")) {
                    relevantCount++;
                }
            }

            return (double) relevantCount / contexts.size();

        } catch (Exception e) {
            log.error("Context precision calculation failed", e);
            return 0.0;
        }
    }

    /**
     * 计算 Context Recall (上下文召回率)
     *
     * 评估上下文中包含 Ground Truth 信息的比例
     */
    private double calculateContextRecall(String question, List<String> contexts, String groundTruth) {
        if (groundTruth == null || groundTruth.isEmpty() || contexts == null || contexts.isEmpty()) {
            return 0.0;
        }

        try {
            String prompt = """
                    评估以下上下文是否能够支持回答 Ground Truth。

                    返回格式 (JSON):
                    {"recall": 0.0-1.0, "reason": "简短说明"}

                    问题: %s
                    Ground Truth: %s
                    上下文:
                    %s

                    请只返回 JSON:
                    """.formatted(question, groundTruth, String.join("\n", contexts));

            String response = chatModel.generate(prompt);
            return parseScore(response, "recall");

        } catch (Exception e) {
            log.error("Context recall calculation failed", e);
            return 0.0;
        }
    }

    /**
     * 计算综合 RAGAS Score
     *
     * 使用加权平均: 0.3 * faithfulness + 0.3 * answer_relevancy + 0.2 * context_precision + 0.2 * context_recall
     */
    private double calculateRagasScore(double faithfulness, double answerRelevancy,
                                       double contextPrecision, double contextRecall) {
        // 各指标权重
        double wFaithfulness = 0.3;
        double wAnswerRelevancy = 0.3;
        double wContextPrecision = 0.2;
        double wContextRecall = 0.2;

        double score = wFaithfulness * faithfulness +
                      wAnswerRelevancy * answerRelevancy +
                      wContextPrecision * contextPrecision +
                      wContextRecall * contextRecall;

        return Math.round(score * 100.0) / 100.0; // 保留两位小数
    }

    /**
     * 解析 JSON 中的 score
     */
    private double parseScore(String response, String fieldName) {
        try {
            int fieldStart = response.indexOf("\"" + fieldName + "\"");
            if (fieldStart == -1) {
                return 0.5; // 默认值
            }
            int colonPos = response.indexOf(":", fieldStart);
            int commaPos = response.indexOf(",", fieldStart);
            int endPos = commaPos != -1 ? commaPos : response.indexOf("}", fieldStart);

            if (colonPos != -1 && endPos != -1) {
                String scoreStr = response.substring(colonPos + 1, endPos).trim();
                return Double.parseDouble(scoreStr);
            }
        } catch (Exception e) {
            log.warn("Failed to parse score from: {}", response);
        }
        return 0.5;
    }

    /**
     * 解析 JSON 数组中的字符串列表
     */
    private List<String> parseStringList(String response) {
        try {
            int arrayStart = response.indexOf('[');
            int arrayEnd = response.lastIndexOf(']');

            if (arrayStart != -1 && arrayEnd != -1) {
                String jsonArray = response.substring(arrayStart, arrayEnd + 1);
                java.util.List<String> items = new java.util.ArrayList<>();
                int start = 0;
                while (true) {
                    int quoteStart = jsonArray.indexOf('"', start);
                    if (quoteStart == -1 || quoteStart > arrayEnd) break;
                    int quoteEnd = jsonArray.indexOf('"', quoteStart + 1);
                    if (quoteEnd == -1 || quoteEnd > arrayEnd) break;
                    items.add(jsonArray.substring(quoteStart + 1, quoteEnd));
                    start = quoteEnd + 1;
                }
                return items;
            }
        } catch (Exception e) {
            log.warn("Failed to parse string list from: {}", response);
        }
        return java.util.List.of();
    }

    /**
     * 计算余弦相似度
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        normA = Math.sqrt(normA);
        normB = Math.sqrt(normB);

        if (normA == 0 || normB == 0) {
            return 0.0;
        }

        return dotProduct / (normA * normB);
    }
}
