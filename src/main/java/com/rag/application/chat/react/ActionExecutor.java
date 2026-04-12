package com.rag.application.chat.react;

import com.rag.application.chat.MemoryService;
import com.rag.application.retrieval.HybridRetrievalService;
import com.rag.application.chat.react.model.Action;
import com.rag.application.chat.react.model.ActionResult;
import com.rag.application.chat.react.model.ActionType;
import com.rag.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    private final HybridRetrievalService hybridRetrievalService;
    private final MemoryService memoryService;
    private final JdbcTemplate jdbcTemplate;
    private final AppConfig.React reactConfig;

    public ActionExecutor(HybridRetrievalService hybridRetrievalService,
                         MemoryService memoryService,
                         JdbcTemplate jdbcTemplate,
                         AppConfig appConfig) {
        this.hybridRetrievalService = hybridRetrievalService;
        this.memoryService = memoryService;
        this.jdbcTemplate = jdbcTemplate;
        this.reactConfig = appConfig.getReact();
    }

    public ActionResult execute(Action action, String query, String kbId) {
        long startTime = System.currentTimeMillis();
        log.info("Executing action: type={}, params={}", action.getType(), action.getParams());

        ActionResult result;
        try {
            result = switch (action.getType()) {
                case RETRIEVE_KNOWLEDGE -> executeKnowledgeRetrieval(query, kbId, action.getParams());
                case QUERY_DATABASE -> executeDatabaseQuery(query, action.getParams());
                case CHECK_CONVERSATION_HISTORY -> executeConversationHistory(query, action.getParams());
                case FINAL_ANSWER -> ActionResult.failure(ActionType.FINAL_ANSWER, "final_answer should not be executed by ActionExecutor");
            };
        } catch (Exception e) {
            log.error("Action execution failed: type={}, error={}", action.getType(), e.getMessage());
            result = ActionResult.failure(action.getType(), e.getMessage());
        }

        result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
        log.info("Action executed: type={}, success={}, time={}ms",
                action.getType(), result.isSuccess(), result.getExecutionTimeMs());

        return result;
    }

    private ActionResult executeKnowledgeRetrieval(String query, String kbId, Map<String, Object> params) {
        if (!reactConfig.getActions().getKnowledge().isEnabled()) {
            return ActionResult.failure(ActionType.RETRIEVE_KNOWLEDGE, "Knowledge retrieval is disabled");
        }

        int topK = reactConfig.getActions().getKnowledge().getTopK();
        if (params != null && params.containsKey("topK")) {
            topK = ((Number) params.get("topK")).intValue();
        }

        try {
            List<HybridRetrievalService.RetrievalResult> results =
                    hybridRetrievalService.hybridSearch(query, kbId, topK);

            if (results.isEmpty()) {
                return ActionResult.success(ActionType.RETRIEVE_KNOWLEDGE, "未找到相关信息");
            }

            String content = results.stream()
                    .map(r -> String.format("[%s] %s", r.chunkId(), r.content()))
                    .collect(Collectors.joining("\n\n"));

            return ActionResult.success(ActionType.RETRIEVE_KNOWLEDGE,
                    String.format("找到 %d 条相关信息:\n%s", results.size(), content));

        } catch (Exception e) {
            log.error("Knowledge retrieval failed: {}", e.getMessage(), e);
            return ActionResult.failure(ActionType.RETRIEVE_KNOWLEDGE, "检索失败: " + e.getMessage());
        }
    }

    private ActionResult executeDatabaseQuery(String query, Map<String, Object> params) {
        if (!reactConfig.getActions().getDatabase().isEnabled()) {
            return ActionResult.failure(ActionType.QUERY_DATABASE, "Database query is disabled");
        }

        String sql = params != null ? (String) params.get("sql") : null;
        if (sql == null || sql.isBlank()) {
            sql = extractSqlFromQuery(query);
        }

        if (sql == null || sql.isBlank()) {
            return ActionResult.failure(ActionType.QUERY_DATABASE, "无法从问题中提取有效的 SQL 查询");
        }

        try {
            log.info("Executing SQL: {}", sql);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

            if (rows.isEmpty()) {
                return ActionResult.success(ActionType.QUERY_DATABASE, "查询结果为空");
            }

            StringBuilder result = new StringBuilder();
            result.append(String.format("查询结果 (%d 行):\n", rows.size()));

            for (int i = 0; i < Math.min(rows.size(), 10); i++) {
                Map<String, Object> row = rows.get(i);
                result.append(String.format("行 %d: %s\n", i + 1, row));
            }

            if (rows.size() > 10) {
                result.append(String.format("... 还有 %d 行", rows.size() - 10));
            }

            return ActionResult.success(ActionType.QUERY_DATABASE, result.toString());

        } catch (Exception e) {
            log.error("Database query failed: {}", e.getMessage(), e);
            return ActionResult.failure(ActionType.QUERY_DATABASE, "数据库查询失败: " + e.getMessage());
        }
    }

    private ActionResult executeConversationHistory(String query, Map<String, Object> params) {
        if (!reactConfig.getActions().getConversation().isEnabled()) {
            return ActionResult.failure(ActionType.CHECK_CONVERSATION_HISTORY, "Conversation history is disabled");
        }

        int windowSize = reactConfig.getActions().getConversation().getWindowSize();
        if (params != null && params.containsKey("windowSize")) {
            windowSize = ((Number) params.get("windowSize")).intValue();
        }

        try {
            String conversationId = params != null ? (String) params.get("conversationId") : null;
            String userId = params != null ? (String) params.get("userId") : null;

            if (conversationId == null || userId == null) {
                return ActionResult.failure(ActionType.CHECK_CONVERSATION_HISTORY, "缺少 conversationId 或 userId 参数");
            }

            MemoryService.ConversationContext ctx = memoryService.getContext(userId, conversationId);

            if ((ctx.summary() == null || ctx.summary().isEmpty()) &&
                (ctx.recentMessages() == null || ctx.recentMessages().isEmpty())) {
                return ActionResult.success(ActionType.CHECK_CONVERSATION_HISTORY, "对话历史为空");
            }

            StringBuilder history = new StringBuilder();
            if (ctx.summary() != null && !ctx.summary().isEmpty()) {
                history.append("【对话摘要】\n").append(ctx.summary()).append("\n\n");
            }

            if (ctx.recentMessages() != null && !ctx.recentMessages().isEmpty()) {
                history.append("【最近消息】\n");
                int count = 0;
                for (MemoryService.Message msg : ctx.recentMessages()) {
                    if (count >= windowSize) break;
                    history.append(msg.role()).append(": ").append(msg.content()).append("\n");
                    count++;
                }
            }

            return ActionResult.success(ActionType.CHECK_CONVERSATION_HISTORY,
                    String.format("对话历史:\n%s", history.toString()));

        } catch (Exception e) {
            log.error("Conversation history query failed: {}", e.getMessage(), e);
            return ActionResult.failure(ActionType.CHECK_CONVERSATION_HISTORY, "查询对话历史失败: " + e.getMessage());
        }
    }

    private String extractSqlFromQuery(String query) {
        String lowerQuery = query.toLowerCase();
        if (lowerQuery.contains("select") && lowerQuery.contains("from")) {
            int selectIdx = lowerQuery.indexOf("select");
            int fromIdx = lowerQuery.indexOf("from");
            if (selectIdx < fromIdx) {
                String sql = query.substring(selectIdx, lowerQuery.indexOf(";", fromIdx) > 0
                        ? lowerQuery.indexOf(";", fromIdx) : query.length());
                return sql.trim();
            }
        }
        return null;
    }

    public boolean isEnabled(ActionType actionType) {
        return switch (actionType) {
            case RETRIEVE_KNOWLEDGE -> reactConfig.getActions().getKnowledge().isEnabled();
            case QUERY_DATABASE -> reactConfig.getActions().getDatabase().isEnabled();
            case CHECK_CONVERSATION_HISTORY -> reactConfig.getActions().getConversation().isEnabled();
            case FINAL_ANSWER -> false;
        };
    }
}