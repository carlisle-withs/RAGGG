package com.rag.application.chat;

import com.rag.application.chat.react.ComplexityRouter;
import com.rag.application.chat.react.ReActContext;
import com.rag.application.chat.react.ReActEngine;
import com.rag.application.chat.react.model.ReActResult;
import com.rag.application.chat.react.model.TaskComplexity;
import com.rag.application.retrieval.RetrievalApplicationService;
import com.rag.domain.model.Conversation;
import com.rag.domain.repository.ConversationRepository;
import com.rag.infrastructure.llm.ChatModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
public class ChatApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ChatApplicationService.class);

    private final ChatModelService chatModel;
    private final MemoryService memoryService;
    private final IntentClassifier intentClassifier;
    private final ComplexityRouter complexityRouter;
    private final ReActEngine reActEngine;
    private final ConversationRepository conversationRepository;
    private final Executor memorySummaryExecutor;
    private final RagContextService ragContextService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public ChatApplicationService(ChatModelService chatModel,
                                  MemoryService memoryService,
                                  IntentClassifier intentClassifier,
                                  ComplexityRouter complexityRouter,
                                  ReActEngine reActEngine,
                                  ConversationRepository conversationRepository,
                                  @Qualifier("memorySummaryThreadPoolExecutor") Executor memorySummaryExecutor,
                                  RagContextService ragContextService) {
        this.chatModel = chatModel;
        this.memoryService = memoryService;
        this.intentClassifier = intentClassifier;
        this.complexityRouter = complexityRouter;
        this.reActEngine = reActEngine;
        this.conversationRepository = conversationRepository;
        this.memorySummaryExecutor = memorySummaryExecutor;
        this.ragContextService = ragContextService;
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
                ReActOutcome outcome = executeReAct(message, kbId, memoryContext, summary);
                response = outcome.answer();
                sources = outcome.sources();
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
                memoryService.addMessage(userId, conversationId, "assistant", response,
                        serializeSources(sources));

                String title = message.length() > 30 ? message.substring(0, 30) : message;
                Optional<Conversation> existing = conversationRepository.findByConversationId(conversationId);
                if (existing.isPresent()) {
                    Conversation conv = existing.get();
                    conv.setLastTime(LocalDateTime.now());
                    conversationRepository.save(conv);
                } else {
                    Conversation conv = new Conversation(conversationId, userId, title);
                    conversationRepository.save(conv);
                }
            }

            log.info("=== Chat Process End ===");
            return new ChatResponse(response, sources, null, conversationId);

        } catch (Exception e) {
            log.error("Chat failed", e);
            return new ChatResponse("抱歉，发生了错误：" + e.getMessage(), List.of(), null, conversationId);
        }
    }

    /**
     * ReAct 分支执行：
     * - 正常完成：聚合 RETRIEVE_KNOWLEDGE 动作携带的检索命中作为 sources（引用溯源）
     * - 降级：回退到真实 RAG 链路重新作答（此前把"执行摘要+降级原因"直接当用户答案返回）
     */
    private ReActOutcome executeReAct(String message, String kbId, String memoryContext, String summary) {
        ReActContext context = new ReActContext(message, memoryContext, summary);
        ReActResult result = reActEngine.execute(message, kbId, context);

        if (!result.isDegraded()) {
            return new ReActOutcome(result.getAnswer(), collectReActSources(result));
        }

        log.info("ReAct degraded ({}), falling back to simple RAG flow", result.getDegradeReason());
        List<RetrievalApplicationService.RetrievalResult> fallbackSources =
                performSimpleFlow(message, kbId, memoryContext);
        String answer = fallbackSources.isEmpty() ? null
                : buildSimpleResponse(message, kbId, memoryContext, fallbackSources);
        return new ReActOutcome(answer, fallbackSources);
    }

    /** 从 ReAct 执行历史聚合检索命中（按 chunkId 去重，取前 5 条） */
    private List<RetrievalApplicationService.RetrievalResult> collectReActSources(ReActResult result) {
        if (result.getActionHistory() == null) {
            return List.of();
        }
        java.util.LinkedHashMap<String, RetrievalApplicationService.RetrievalResult> dedup = new java.util.LinkedHashMap<>();
        for (ReActResult.ActionRecord record : result.getActionHistory()) {
            if (record.getResult() != null && record.getResult().getSources() != null) {
                for (Object s : record.getResult().getSources()) {
                    if (s instanceof RetrievalApplicationService.RetrievalResult r
                            && !dedup.containsKey(r.chunkId())) {
                        dedup.put(r.chunkId(), r);
                    }
                }
            }
            if (dedup.size() >= 5) break;
        }
        return List.copyOf(dedup.values());
    }

    /** ReAct 分支结果：answer 可为 null（由外层兜底链路处理），sources 用于引用溯源 */
    private record ReActOutcome(
            String answer,
            List<RetrievalApplicationService.RetrievalResult> sources
    ) {}

    private List<RetrievalApplicationService.RetrievalResult> performSimpleFlow(String message, String kbId, String memoryContext) {
        IntentClassifier.IntentResult intentResult = intentClassifier.classify(message);
        log.info("Intent: {}, Confidence: {}", intentResult.intent(), intentResult.confidence());

        if (intentClassifier.needsRetrieval(intentResult)) {
            return performRAG(message, kbId, memoryContext);
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
     * 执行 RAG 流程：委托给统一的 RagContextService（指代消解 + 查询扩展 + 混合检索）。
     * 流式链路与 v3 链路共用同一实现，避免多份管线各自漂移。
     */
    private List<RetrievalApplicationService.RetrievalResult> performRAG(String message, String kbId, String memoryContext) {
        List<RetrievalApplicationService.RetrievalResult> results =
                ragContextService.build(message, memoryContext, kbId).sources();
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
    /** 检索命中 → JSON 数组持久化（空列表返回 null） */
    private String serializeSources(List<RetrievalApplicationService.RetrievalResult> sources) {
        if (sources == null || sources.isEmpty()) return null;
        try {
            List<Map<String, Object>> simplified = sources.stream()
                    .map(s -> Map.<String, Object>of(
                            "chunkId", s.chunkId(),
                            "content", s.content() != null && s.content().length() > 200
                                    ? s.content().substring(0, 200) + "…" : s.content(),
                            "score", s.score()))
                    .toList();
            return objectMapper.writeValueAsString(simplified);
        } catch (Exception e) {
            return null;
        }
    }

    public record ChatResponse(
            String message,
            List<RetrievalApplicationService.RetrievalResult> sources,
            IntentClassifier.IntentResult intent,
            String conversationId
    ) {}
}
