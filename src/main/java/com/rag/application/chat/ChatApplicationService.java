package com.rag.application.chat;

import com.rag.application.chat.react.ComplexityRouter;
import com.rag.application.chat.react.ReActContext;
import com.rag.application.chat.react.ReActEngine;
import com.rag.application.chat.react.model.ReActResult;
import com.rag.application.chat.react.model.TaskComplexity;
import com.rag.application.retrieval.RetrievalApplicationService;
import com.rag.infrastructure.llm.ChatModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ChatApplicationService.class);

    private final ChatModelService chatModel;
    private final RetrievalApplicationService retrievalService;
    private final MemoryService memoryService;
    private final IntentClassifier intentClassifier;
    private final QueryRewriter queryRewriter;
    private final ComplexityRouter complexityRouter;
    private final ReActEngine reActEngine;

    public ChatApplicationService(ChatModelService chatModel,
                                  RetrievalApplicationService retrievalService,
                                  MemoryService memoryService,
                                  IntentClassifier intentClassifier,
                                  QueryRewriter queryRewriter,
                                  ComplexityRouter complexityRouter,
                                  ReActEngine reActEngine) {
        this.chatModel = chatModel;
        this.retrievalService = retrievalService;
        this.memoryService = memoryService;
        this.intentClassifier = intentClassifier;
        this.queryRewriter = queryRewriter;
        this.complexityRouter = complexityRouter;
        this.reActEngine = reActEngine;
    }

    /**
     * 聊天入口方法
     *
     * 完整流程:
     * 1. 获取会话上下文 (记忆)
     * 2. 复杂度路由
     * 3. 意图识别
     * 4. 根据复杂度执行:
     *    - 简单: 原流程 (意图识别 → RAG → 生成)
     *    - 复杂: ReAct 引擎
     * 5. 保存对话记忆
     */
    public ChatResponse chat(String message, String kbId, String userId, String conversationId) {
        log.info("=== Chat Process Start ===");
        log.info("Message: {}, KB: {}, User: {}, Conv: {}", message, kbId, userId, conversationId);

        try {
            String memoryContext = "";
            String summary = null;
            if (conversationId != null && userId != null) {
                memoryContext = memoryService.buildContextPrompt(userId, conversationId);
                MemoryService.ConversationContext ctx = memoryService.getContext(userId, conversationId);
                summary = ctx.summary();
                log.info("Memory context loaded: {} chars", memoryContext.length());
            }

            TaskComplexity complexity = complexityRouter.classify(message, memoryContext);
            log.info("Task complexity: {}", complexity);

            String response;
            List<RetrievalApplicationService.RetrievalResult> sources;

            if (complexity == TaskComplexity.COMPLEX && reActEngine.isEnabled()) {
                log.info("Using ReAct engine for complex task");
                response = executeReAct(message, kbId, memoryContext, summary);
                sources = List.of();
            } else {
                log.info("Using simple RAG flow");
                sources = performSimpleFlow(message, kbId, memoryContext);
                response = sources.isEmpty() ? null : buildSimpleResponse(message, kbId, memoryContext, sources);
            }

            if (response == null) {
                IntentClassifier.IntentResult intentResult = intentClassifier.classify(message);
                String prompt = buildPrompt(message, buildContext(sources), memoryContext, intentResult);
                response = chatModel.generate(prompt);
            }

            if (conversationId != null && userId != null) {
                memoryService.addMessage(userId, conversationId, "user", message);
                memoryService.addMessage(userId, conversationId, "assistant", response);
            }

            log.info("=== Chat Process End ===");
            return new ChatResponse(response, sources, null, conversationId);

        } catch (Exception e) {
            log.error("Chat failed", e);
            return new ChatResponse("抱歉，发生了错误：" + e.getMessage(), List.of(), null, conversationId);
        }
    }

    private String executeReAct(String message, String kbId, String memoryContext, String summary) {
        ReActContext context = new ReActContext(message, memoryContext, summary);
        ReActResult result = reActEngine.execute(message, kbId, context);

        if (result.isDegraded()) {
            log.info("ReAct degraded, using fallback response");
            return result.getAnswer();
        }

        return result.getAnswer();
    }

    private List<RetrievalApplicationService.RetrievalResult> performSimpleFlow(String message, String kbId, String memoryContext) {
        IntentClassifier.IntentResult intentResult = intentClassifier.classify(message);
        log.info("Intent: {}, Confidence: {}", intentResult.intent(), intentResult.confidence());

        if (intentClassifier.needsRetrieval(intentResult)) {
            return performRAG(message, kbId);
        }
        return List.of();
    }

    private String buildSimpleResponse(String message, String kbId, String memoryContext,
                                      List<RetrievalApplicationService.RetrievalResult> sources) {
        String ragContext = buildContext(sources);
        IntentClassifier.IntentResult intentResult = intentClassifier.classify(message);
        String prompt = buildPrompt(message, ragContext, memoryContext, intentResult);
        return chatModel.generate(prompt);
    }

    /**
     * 执行 RAG 流程: 查询改写 + 检索
     */
    private List<RetrievalApplicationService.RetrievalResult> performRAG(String message, String kbId) {
        // 1. 查询改写 (扩展)
        String expandedQuery = queryRewriter.expand(message);
        log.info("Expanded query: {}", expandedQuery);

        // 2. 使用混合检索
        List<RetrievalApplicationService.RetrievalResult> results =
                retrievalService.hybridSearch(expandedQuery, kbId, 5);

        log.info("Retrieved {} sources", results.size());
        return results;
    }

    /**
     * 构建上下文字符串
     */
    private String buildContext(List<RetrievalApplicationService.RetrievalResult> sources) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        return sources.stream()
                .map(s -> "【文档】" + s.content())
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 构建 Prompt
     */
    private String buildPrompt(String message, String ragContext, String memoryContext,
                               IntentClassifier.IntentResult intentResult) {
        StringBuilder prompt = new StringBuilder();

        // 1. 记忆上下文
        if (!memoryContext.isEmpty()) {
            prompt.append("【对话历史】\n").append(memoryContext).append("\n\n");
        }

        // 2. RAG 上下文
        if (!ragContext.isEmpty()) {
            prompt.append("【参考文档】\n").append(ragContext).append("\n\n");
        }

        // 3. 当前问题
        prompt.append("【当前问题】\n").append(message).append("\n\n");

        // 4. 指令
        if (!ragContext.isEmpty()) {
            prompt.append("请基于参考文档回答当前问题。如果参考文档中没有相关信息，请明确说明。");
            if ("clarification".equals(intentResult.intent().name().toLowerCase())) {
                prompt.append("\n如果问题不明确，请要求用户澄清。");
            }
        } else {
            prompt.append("请结合对话历史直接回答当前问题。");
        }

        return prompt.toString();
    }

    /**
     * 响应 record
     *
     * @param message AI 生成的回复
     * @param sources 检索到的相关文档
     * @param intent 识别的意图
     * @param conversationId 对话 ID
     */
    public record ChatResponse(
            String message,
            List<RetrievalApplicationService.RetrievalResult> sources,
            IntentClassifier.IntentResult intent,
            String conversationId
    ) {}
}
