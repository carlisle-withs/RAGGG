package com.rag.application.chat;

import com.rag.application.retrieval.RetrievalApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
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

    private final QueryRewriter queryRewriter;
    private final RetrievalApplicationService retrievalService;

    public RagContextService(QueryRewriter queryRewriter,
                             RetrievalApplicationService retrievalService) {
        this.queryRewriter = queryRewriter;
        this.retrievalService = retrievalService;
    }

    /**
     * @param userMessage   用户原始问题
     * @param memoryContext 记忆上下文（空表示单轮对话，跳过指代消解）
     * @param kbId          知识库 ID（空表示纯闲聊，跳过检索）
     */
    public RagContext build(String userMessage, String memoryContext, String kbId) {
        // 1. 指代消解：借助记忆把追问改写为独立完整问题，避免原话直接向量化召回脱靶
        String searchQuery = userMessage;
        if (memoryContext != null && !memoryContext.isEmpty()) {
            String condensed = queryRewriter.condenseWithMemory(userMessage, memoryContext);
            if (condensed != null) {
                searchQuery = condensed;
            }
        }

        // 2. 查询扩展（同义词/术语扩展），失败退回消解后的查询
        String expandedQuery = searchQuery;
        try {
            expandedQuery = queryRewriter.expand(searchQuery);
            log.info("Expanded query: {}", expandedQuery);
        } catch (Exception e) {
            log.warn("Query expansion failed, fallback to condensed query: {}", e.getMessage());
        }

        // 3. 混合检索（含重排），失败返回空来源
        List<RetrievalApplicationService.RetrievalResult> sources = List.of();
        if (kbId != null && !kbId.isEmpty()) {
            try {
                sources = retrievalService.hybridSearch(expandedQuery, kbId, 5, true);
                log.info("Retrieved {} sources", sources.size());
            } catch (Exception e) {
                log.warn("Retrieval failed, fallback to empty sources: {}", e.getMessage());
            }
        }

        return new RagContext(expandedQuery, sources, buildContextText(sources));
    }

    private String buildContextText(List<RetrievalApplicationService.RetrievalResult> sources) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        return sources.stream()
                .map(s -> "【文档】" + s.content())
                .collect(Collectors.joining("\n\n"));
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
