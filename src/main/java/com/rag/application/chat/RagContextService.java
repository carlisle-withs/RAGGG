package com.rag.application.chat;

import com.rag.application.retrieval.RetrievalApplicationService;
import com.rag.domain.model.RagTraceNode;
import com.rag.domain.model.RagTraceRun;
import com.rag.domain.repository.RagTraceNodeRepository;
import com.rag.domain.repository.RagTraceRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 统一的 RAG 检索管线：指代消解 → 查询扩展 → 混合检索 → 上下文拼装。
 *
 * 同步链路（ChatApplicationService）、流式链路（DeepSeekStreamingService）、
 * v3 SSE 链路（RagChatController）共用本服务，任何管线调整只改这一处，
 * 避免三份实现各自漂移（历史上 v3 缺指代消解、流式缺查询扩展就是这么来的）。
 *
 * 每一步失败都降级为"用上一步的结果继续"，检索失败时返回空上下文，
 * 由调用方决定无上下文时的兜底话术——保证"检索挂了也能聊"。
 */
@Service
public class RagContextService {

    private static final Logger log = LoggerFactory.getLogger(RagContextService.class);

    /** 检索上下文 token 预算：top-5 片段拼接超出预算时优先保留高相关（靠前）片段 */
    private final int contextMaxTokens;

    private final QueryRewriter queryRewriter;
    private final RetrievalApplicationService retrievalService;
    private final QueryTermService queryTermService;
    private final RagTraceRunRepository traceRunRepository;
    private final RagTraceNodeRepository traceNodeRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagContextService(QueryRewriter queryRewriter,
                             RetrievalApplicationService retrievalService,
                             @Value("${retrieval.context-max-tokens:4000}") int contextMaxTokens,
                             QueryTermService queryTermService,
                             RagTraceRunRepository traceRunRepository,
                             RagTraceNodeRepository traceNodeRepository) {
        this.queryRewriter = queryRewriter;
        this.retrievalService = retrievalService;
        this.contextMaxTokens = contextMaxTokens;
        this.queryTermService = queryTermService;
        this.traceRunRepository = traceRunRepository;
        this.traceNodeRepository = traceNodeRepository;
    }

    /**
     * @param userMessage   用户原始问题
     * @param memoryContext 记忆上下文（空表示单轮对话，跳过指代消解）
     * @param kbId          知识库 ID（空表示纯闲聊，跳过检索）
     */
    public RagContext build(String userMessage, String memoryContext, String kbId) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long traceStart = System.currentTimeMillis();
        List<RagTraceNode> traceNodes = new ArrayList<>();
        boolean success = true;
        String errorMessage = null;

        // 1. 指代消解：借助记忆把追问改写为独立完整问题，避免原话直接向量化召回脱靶
        String searchQuery = userMessage;
        long stepStart = System.currentTimeMillis();
        if (memoryContext != null && !memoryContext.isEmpty()) {
            try {
                String condensed = queryRewriter.condenseWithMemory(userMessage, memoryContext);
                if (condensed != null) {
                    searchQuery = condensed;
                }
            } catch (Exception e) {
                success = false;
                errorMessage = "condense: " + e.getMessage();
                log.warn("Condense failed: {}", e.getMessage());
            }
        }
        traceNodes.add(traceNode(traceId, "condense", ms(stepStart),
                Map.of("applied", !searchQuery.equals(userMessage), "query", searchQuery)));

        // 1.5 术语归一化：查询词映射表（t_query_term_mapping）把同义词改写为标准术语
        stepStart = System.currentTimeMillis();
        String mappedQuery = queryTermService.applyMappings(searchQuery);
        if (!mappedQuery.equals(searchQuery)) {
            traceNodes.add(traceNode(traceId, "term_mapping", ms(stepStart),
                    Map.of("before", searchQuery, "after", mappedQuery)));
            searchQuery = mappedQuery;
        }

        // 2. 查询扩展（同义词/术语扩展），失败退回消解后的查询
        String expandedQuery = searchQuery;
        stepStart = System.currentTimeMillis();
        try {
            expandedQuery = queryRewriter.expand(searchQuery);
            log.info("Expanded query: {}", expandedQuery);
        } catch (Exception e) {
            success = false;
            errorMessage = "expand: " + e.getMessage();
            log.warn("Query expansion failed, fallback to condensed query: {}", e.getMessage());
        }
        if (expandedQuery == null || expandedQuery.isBlank()) {
            expandedQuery = searchQuery; // 扩展器返回空时回退，绝不把 null 带进下游
        }
        traceNodes.add(traceNode(traceId, "expand", ms(stepStart),
                Map.of("expanded", expandedQuery)));

        // 3. 混合检索（含重排），失败返回空来源
        List<RetrievalApplicationService.RetrievalResult> sources = List.of();
        stepStart = System.currentTimeMillis();
        if (kbId != null && !kbId.isEmpty()) {
            try {
                sources = retrievalService.hybridSearch(expandedQuery, kbId, 5, true);
                log.info("Retrieved {} sources", sources.size());
            } catch (Exception e) {
                success = false;
                errorMessage = "retrieval: " + e.getMessage();
                log.warn("Retrieval failed, fallback to empty sources: {}", e.getMessage());
            }
        }
        traceNodes.add(traceNode(traceId, "hybrid_retrieval", ms(stepStart),
                Map.of("kbId", kbId == null ? "" : kbId, "sourceCount", sources.size())));

        RagContext context = new RagContext(expandedQuery, sources, buildContextText(sources));

        stepStart = System.currentTimeMillis();
        traceNodes.add(traceNode(traceId, "context_assembly", ms(stepStart), Map.of(
                "contextChars", context.contextText().length(),
                "budgetTokens", contextMaxTokens)));

        persistTrace(traceId, traceNodes, success, errorMessage,
                System.currentTimeMillis() - traceStart, expandedQuery, kbId, sources.size());

        return context;
    }

    // ---- trace 落库（失败静默：链路追踪绝不能影响主流程） ----

    private long ms(long startNanosOrMillis) {
        return System.currentTimeMillis() - startNanosOrMillis;
    }

    private RagTraceNode traceNode(String traceId, String step, long durationMs, Map<String, Object> extra) {
        RagTraceNode node = new RagTraceNode();
        node.setTraceId(traceId);
        node.setNodeId(traceId + "-" + step);
        node.setDepth(0);
        node.setNodeType("step");
        node.setNodeName(step);
        node.setClassName(RagContextService.class.getName());
        node.setMethodName("build");
        node.setStatus(RagTraceNode.STATUS_COMPLETED);
        node.setDurationMs(durationMs);
        node.setStartTime(LocalDateTime.now().minusNanos(durationMs * 1_000_000));
        node.setEndTime(LocalDateTime.now());
        try {
            node.setExtraData(objectMapper.writeValueAsString(extra));
        } catch (Exception ignore) { }
        return node;
    }

    private void persistTrace(String traceId, List<RagTraceNode> nodes, boolean success,
                              String errorMessage, long totalMs,
                              String searchQuery, String kbId, int sourceCount) {
        try {
            RagTraceRun run = new RagTraceRun();
            run.setTraceId(traceId);
            run.setTraceName("rag-context");
            run.setEntryMethod("RagContextService.build");
            run.setStatus(success ? RagTraceRun.STATUS_COMPLETED : RagTraceRun.STATUS_FAILED);
            run.setErrorMessage(errorMessage);
            run.setStartTime(LocalDateTime.now().minusNanos(totalMs * 1_000_000));
            run.setEndTime(LocalDateTime.now());
            run.setDurationMs(totalMs);
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("searchQuery", searchQuery);
            extra.put("sourceCount", sourceCount);
            run.setExtraData(objectMapper.writeValueAsString(extra));
            traceRunRepository.save(run);
            traceNodeRepository.saveAll(nodes);
        } catch (Exception e) {
            log.debug("Trace persistence failed (ignored): {}", e.getMessage());
        }
    }

    private String buildContextText(List<RetrievalApplicationService.RetrievalResult> sources) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        // token 预算：与记忆系统同款的估算口径（CJK 1 字 1 token，其余 4 字符 1 token）。
        // 超预算时按相关性顺序（sources 已按分数降序）截断，保证上下文不无限膨胀稀释注意力。
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int kept = 0;
        for (RetrievalApplicationService.RetrievalResult s : sources) {
            int tokens = estimateTokens(s.content());
            if (used + tokens > contextMaxTokens && kept > 0) {
                log.info("Retrieval context token budget exceeded: used={}, next={}, budget={} — kept {}/{} sources",
                        used, tokens, contextMaxTokens, kept, sources.size());
                break;
            }
            sb.append("【文档】").append(s.content()).append("\n\n");
            used += tokens;
            kept++;
        }
        return sb.toString().stripTrailing();
    }

    /** 与 MemoryService.estimateTokens 同口径的保守估算 */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x2E80 && c <= 0x9FFF) || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xFF00 && c <= 0xFFEF)) {
                cjk++;
            }
        }
        return cjk + (text.length() - cjk) / 4;
    }

    /**
     * 检索结果与拼装好的上下文文本
     *
     * @param searchQuery 实际用于检索的查询（消解+扩展后）
     * @param sources     检索到的来源（用于前端溯源展示）
     * @param contextText 拼装好的【文档】上下文文本
     */
    public record RagContext(
            String searchQuery,
            List<RetrievalApplicationService.RetrievalResult> sources,
            String contextText
    ) {}
}
