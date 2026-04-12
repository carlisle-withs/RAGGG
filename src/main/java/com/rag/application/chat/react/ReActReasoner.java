package com.rag.application.chat.react;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.application.chat.react.model.Action;
import com.rag.application.chat.react.model.ActionType;
import com.rag.application.chat.react.model.Thought;
import com.rag.config.AppConfig;
import com.rag.infrastructure.llm.ChatModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ReActReasoner {

    private static final Logger log = LoggerFactory.getLogger(ReActReasoner.class);

    private final AppConfig.React reactConfig;
    private final ChatModelService chatModelService;
    private final ObjectMapper objectMapper;

    public ReActReasoner(AppConfig appConfig,
                         ChatModelService chatModelService,
                         ObjectMapper objectMapper) {
        this.reactConfig = appConfig.getReact();
        this.chatModelService = chatModelService;
        this.objectMapper = objectMapper;
    }

    public Thought reason(String query, ReActContext context) {
        String prompt = buildReasoningPrompt(query, context);

        try {
            String response = chatModelService.generate(prompt);
            return parseResponse(response);

        } catch (Exception e) {
            log.error("ReAct reasoning failed: {}", e.getMessage());
            return Thought.finalAnswer("推理失败，降级到简单流程", "抱歉，我无法完成这个复杂的推理任务。");
        }
    }

    private String buildReasoningPrompt(String query, ReActContext context) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("你是一个智能助手，正在通过推理和行动来回答用户问题。\n\n");

        prompt.append("当前任务：\n").append(query).append("\n\n");

        if (context.getSummary() != null && !context.getSummary().isBlank()) {
            prompt.append("【对话摘要】\n").append(context.getSummary()).append("\n\n");
        }

        if (context.getObservations() != null && !context.getObservations().isEmpty()) {
            prompt.append("【已执行的步骤和结果】\n");
            prompt.append(context.getFormattedHistory());
            prompt.append("\n");
        }

        prompt.append("""
            可用Action：
            1. retrieve_knowledge: 从知识库检索相关信息
            2. query_database: 从数据库查询结构化数据
            3. check_conversation_history: 查询对话历史
            4. final_answer: 生成最终答案

            请分析当前状态，决定下一步Action。

            如果问题已经解决，输出 final_answer。
            如果需要更多信息，选择一个Action并说明理由。

            输出格式：
            Reasoning: 你的思考过程
            Action: 选择的Action (retrieve_knowledge/query_database/check_conversation_history/final_answer)
            Params: {"param": "value"} (如需要)
            """);

        return prompt.toString();
    }

    private Thought parseResponse(String response) {
        try {
            String reasoning = extractField(response, "Reasoning");
            String actionStr = extractField(response, "Action");
            String paramsStr = extractField(response, "Params");

            if (actionStr == null) {
                if (response.toLowerCase().contains("final_answer")) {
                    return Thought.finalAnswer(reasoning != null ? reasoning : "", extractAnswer(response));
                }
                return Thought.finalAnswer("无法解析Action，降级到简单流程", "抱歉，我无法完成这个任务。");
            }

            ActionType actionType = parseActionType(actionStr.trim());
            Map<String, Object> params = parseParams(paramsStr);

            if (actionType == ActionType.FINAL_ANSWER) {
                return Thought.finalAnswer(reasoning != null ? reasoning : "", extractAnswer(response));
            }

            Action action = new Action(actionType, params, reasoning);
            return Thought.action(reasoning != null ? reasoning : "", action);

        } catch (Exception e) {
            log.error("Failed to parse ReAct response: {}", e.getMessage());
            return Thought.finalAnswer("解析失败，降级到简单流程", "抱歉，我无法完成这个任务。");
        }
    }

    private String extractField(String response, String fieldName) {
        String[] lines = response.split("\n");
        for (String line : lines) {
            if (line.startsWith(fieldName + ":") || line.startsWith(fieldName.toLowerCase() + ":")) {
                int colonIdx = line.indexOf(":");
                return line.substring(colonIdx + 1).trim();
            }
        }
        return null;
    }

    private ActionType parseActionType(String actionStr) {
        String lower = actionStr.toLowerCase().trim();
        if (lower.contains("retrieve_knowledge") || lower.contains("knowledge")) {
            return ActionType.RETRIEVE_KNOWLEDGE;
        } else if (lower.contains("query_database") || lower.contains("database")) {
            return ActionType.QUERY_DATABASE;
        } else if (lower.contains("check_conversation") || lower.contains("history")) {
            return ActionType.CHECK_CONVERSATION_HISTORY;
        } else {
            return ActionType.FINAL_ANSWER;
        }
    }

    private Map<String, Object> parseParams(String paramsStr) {
        Map<String, Object> params = new HashMap<>();
        if (paramsStr == null || paramsStr.isBlank() || paramsStr.equals("{}")) {
            return params;
        }

        try {
            if (paramsStr.startsWith("{")) {
                JsonNode node = objectMapper.readTree(paramsStr);
                node.fields().forEachRemaining(entry ->
                        params.put(entry.getKey(), entry.getValue().asText()));
            }
        } catch (Exception e) {
            log.warn("Failed to parse params: {}", e.getMessage());
        }

        return params;
    }

    private String extractAnswer(String response) {
        String reasoning = extractField(response, "Reasoning");
        if (reasoning != null && !reasoning.isBlank()) {
            return reasoning;
        }

        String[] lines = response.split("\n");
        StringBuilder answer = new StringBuilder();
        boolean capture = false;

        for (String line : lines) {
            if (line.toLowerCase().contains("final_answer") || capture) {
                capture = true;
                int colonIdx = line.indexOf(":");
                if (colonIdx >= 0 && line.length() > colonIdx + 1) {
                    answer.append(line.substring(colonIdx + 1).trim()).append(" ");
                }
            }
        }

        return answer.length() > 0 ? answer.toString().trim() : "无法生成答案";
    }
}