package com.rag.application.retrieval;

import com.rag.application.retrieval.HybridRetrievalService.RetrievalResult;
import com.rag.infrastructure.llm.EmbeddingService;
import com.rag.infrastructure.search.ElasticsearchSearch;
import com.rag.infrastructure.vector.MilvusVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Hierarchical Retrieval Service
 *
 * 实现 Sentence Window + Auto-Merging 检索策略：
 *
 * 1. Sentence-Level 细粒度检索（召回阶段，取 topK * 3）
 * 2. Auto-Merging：命中的 Sentence 集中在同一 Parent 时，
 *    用 Parent 替换被合并的 Sentence，提升上下文完整性
 * 3. Sentence Window：保留为 Sentence 的节点，用 windowContent 替换内容，
 *    扩展上下文
 * 4. CrossEncoder 精排（由调用方负责）
 */
@Service
public class HierarchicalRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalRetrievalService.class);

    private final MilvusVectorStore milvusVectorStore;
    private final ElasticsearchSearch elasticsearchSearch;
    private final EmbeddingService embeddingService;
    private final HybridRetrievalService hybridRetrievalService;
    private final Executor retrievalExecutor;

    // 配置参数
    private final double mergingRatio;  // 合并阈值：命中 sentence 数 / 总 sentence 数
    private final int sentenceWindowSize;

    public HierarchicalRetrievalService(
            MilvusVectorStore milvusVectorStore,
            ElasticsearchSearch elasticsearchSearch,
            EmbeddingService embeddingService,
            HybridRetrievalService hybridRetrievalService,
            @Qualifier("retrievalThreadPoolExecutor") Executor retrievalExecutor,
            @Value("${retrieval.swa.merging-ratio:0.5}") double mergingRatio,
            @Value("${retrieval.swa.window-size:3}") int sentenceWindowSize) {

        this.milvusVectorStore = milvusVectorStore;
        this.elasticsearchSearch = elasticsearchSearch;
        this.embeddingService = embeddingService;
        this.hybridRetrievalService = hybridRetrievalService;
        this.retrievalExecutor = retrievalExecutor;
        this.mergingRatio = mergingRatio;
        this.sentenceWindowSize = sentenceWindowSize;
    }

    /**
     * 层级检索主流程：
     * Sentence 召回 → Auto-Merging → Sentence Window 扩展
     */
    public List<RetrievalResult> retrieve(String query, String kbId, int topK) {
        log.info("[HierarchicalRetrieval] Query: {}, KB: {}, TopK: {}",
            truncate(query, 60), kbId, topK);

        // Step 1: 细粒度 Sentence 检索（取 topK * 3 确保有足够候选）
        long step1Start = System.currentTimeMillis();
        List<HybridRetrievalService.RetrievalResult> milvusResults =
                hybridRetrievalService.hybridSearch(query, kbId, topK * 3);
        // 转换为 HierarchicalRetrievalService.RetrievalResult
        List<RetrievalResult> sentenceCandidates = milvusResults.stream()
                .map(r -> new RetrievalResult(r.chunkId(), r.content(), r.score(), r.relevance()))
                .collect(Collectors.toList());
        long step1Time = System.currentTimeMillis() - step1Start;

        if (sentenceCandidates.isEmpty()) {
            log.info("[HierarchicalRetrieval] No candidates found");
            return Collections.emptyList();
        }

        log.info("[HierarchicalRetrieval] Step1(Sentence检索): {} candidates in {}ms",
            sentenceCandidates.size(), step1Time);

        // 打印候选信息
        for (int i = 0; i < Math.min(3, sentenceCandidates.size()); i++) {
            RetrievalResult r = sentenceCandidates.get(i);
            Map<String, String> meta = r.metadata();
            log.debug("[HierarchicalRetrieval] Candidate[{}]: chunkId={}, chunkLevel={}, score={:.4f}",
                i, r.chunkId(),
                meta != null ? meta.get("chunkLevel") : "unknown",
                r.score());
        }

        // Step 2: Auto-Merging
        long step2Start = System.currentTimeMillis();
        List<RetrievalResult> mergedResults = autoMerge(sentenceCandidates, topK * 2);
        long step2Time = System.currentTimeMillis() - step2Start;
        log.info("[HierarchicalRetrieval] Step2(Auto-Merging): {} results in {}ms",
            mergedResults.size(), step2Time);

        // Step 3: Sentence Window 扩展
        long step3Start = System.currentTimeMillis();
        List<RetrievalResult> windowExpanded = expandSentenceWindow(mergedResults);
        long step3Time = System.currentTimeMillis() - step3Start;
        log.info("[HierarchicalRetrieval] Step3(Window扩展): {} results in {}ms",
            windowExpanded.size(), step3Time);

        // 按分数降序取 topK
        List<RetrievalResult> finalResults = windowExpanded.stream()
                .sorted(Comparator.comparingDouble(RetrievalResult::score).reversed())
                .limit(topK)
                .collect(Collectors.toList());

        log.info("[HierarchicalRetrieval] Final results: {}", finalResults.size());
        return finalResults;
    }

    /**
     * Auto-Merging 逻辑
     *
     * 核心思想：如果多个 Sentence 命中了同一个 Parent 节点，
     * 说明这个 Parent 的整体相关性更高，应该替换为 Parent 节点
     *
     * 合并分数 = (命中的 Sentence 数 / Parent 总 Sentence 数) * 平均 RRF 分数
     * 如果 mergeScore / avgSentenceScore > mergingRatio，则合并
     */
    private List<RetrievalResult> autoMerge(
            List<RetrievalResult> candidates, int limit) {

        // 按 parentId 分组
        Map<String, List<RetrievalResult>> groupedByParent = new LinkedHashMap<>();
        List<RetrievalResult> orphans = new ArrayList<>(); // 没有 parentId 的 chunk

        for (RetrievalResult candidate : candidates) {
            Map<String, String> meta = candidate.metadata();
            String chunkLevel = meta != null ? meta.get("chunkLevel") : "unknown";

            // 如果已经是 parent chunk，直接保留
            if ("parent".equals(chunkLevel)) {
                String parentId = candidate.chunkId();
                groupedByParent.computeIfAbsent(parentId, k -> new ArrayList<>())
                        .add(candidate);
                continue;
            }

            // Leaf chunk：按 parentId 分组
            String parentId = meta != null ? meta.get("parentChunkId") : null;
            if (parentId != null) {
                groupedByParent.computeIfAbsent(parentId, k -> new ArrayList<>())
                        .add(candidate);
            } else {
                orphans.add(candidate);
            }
        }

        List<RetrievalResult> finalResults = new ArrayList<>();
        Set<String> consumedLeafIds = new HashSet<>();

        // 遍历每个 Parent 组
        for (Map.Entry<String, List<RetrievalResult>> entry : groupedByParent.entrySet()) {
            List<RetrievalResult> leaves = entry.getValue();

            // 获取 Parent 的 siblingCount（该 Parent 下有多少叶子）
            int totalSiblings = 0;
            double avgLeafScore = 0.0;
            for (RetrievalResult leaf : leaves) {
                Map<String, String> meta = leaf.metadata();
                int siblings = 1; // 默认为1
                if (meta != null && meta.get("siblingCount") != null) {
                    siblings = Integer.parseInt(meta.get("siblingCount"));
                }
                totalSiblings = Math.max(totalSiblings, siblings);
                avgLeafScore += leaf.score();
            }
            if (!leaves.isEmpty()) {
                avgLeafScore /= leaves.size();
            }

            // 计算合并分数
            double hitRatio = (double) leaves.size() / Math.max(1, totalSiblings);
            double mergeScore = hitRatio * avgLeafScore;

            // 统计元信息
            String windowContent = "";
            String parentContent = "";
            String parentChunkId = entry.getKey();

            // 收集信息
            List<String> leafContents = new ArrayList<>();
            for (RetrievalResult leaf : leaves) {
                Map<String, String> meta = leaf.metadata();
                consumedLeafIds.add(leaf.chunkId());
                if (meta != null) {
                    if (meta.get("windowContent") != null) {
                        windowContent = meta.get("windowContent");
                    }
                }
                leafContents.add(leaf.content());
            }

            // 如果命中比例超过阈值，使用合并结果
            if (hitRatio >= mergingRatio && leaves.size() >= minLeavesToMerge()) {
                // 构建 Parent 替代内容（所有叶子拼接）
                parentContent = String.join(" ", leafContents);

                // Parent 的分数 = 合并分数 + 叶子平均分的加权
                double parentScore = mergeScore + avgLeafScore * 0.5;

                log.debug("[AutoMerge] Parent {} merged from {} leaves, hitRatio={:.2f}, score={:.4f}",
                    parentChunkId, leaves.size(), hitRatio, parentScore);

                finalResults.add(new RetrievalResult(
                        parentChunkId,
                        parentContent,
                        parentScore,
                        parentScore,
                        buildMergeMetadata(leaves, windowContent)
                ));
            } else {
                // 合并比例不足，保留原始叶子
                log.debug("[AutoMerge] Parent {} NOT merged, hitRatio={:.2f} < {}",
                    parentChunkId, hitRatio, mergingRatio);
                finalResults.addAll(leaves);
            }
        }

        // 保留孤立的 chunks（没有 parentId 的）
        for (RetrievalResult orphan : orphans) {
            if (!consumedLeafIds.contains(orphan.chunkId())) {
                finalResults.add(orphan);
            }
        }

        // 按分数降序
        finalResults.sort(Comparator.comparingDouble(RetrievalResult::score).reversed());

        return finalResults.stream().limit(limit).collect(Collectors.toList());
    }

    private int minLeavesToMerge() {
        return 2; // 至少 2 个叶子命中才考虑合并
    }

    /**
     * Sentence Window 扩展
     *
     * 对于保留为 Leaf 的节点，用 windowContent 替换原有内容，
     * 实现上下文扩展（前后各 N 句）
     */
    private List<RetrievalResult> expandSentenceWindow(List<RetrievalResult> candidates) {
        return candidates.stream().map(result -> {
            Map<String, String> meta = result.metadata();
            if (meta == null) {
                return result;
            }

            String chunkLevel = meta.get("chunkLevel");
            String windowContent = meta.get("windowContent");

            // 仅对 Leaf 节点进行 Window 扩展
            if ("leaf".equals(chunkLevel) && windowContent != null && !windowContent.isEmpty()) {
                log.debug("[SentenceWindow] Expanding chunk {} with {} chars window content",
                    result.chunkId(), windowContent.length());

                return new RetrievalResult(
                        result.chunkId(),
                        windowContent,  // 用扩展窗口内容替换原始句子
                        result.score(),
                        result.relevance(),
                        buildWindowMetadata(meta, result.content())
                );
            }

            return result;
        }).collect(Collectors.toList());
    }

    private Map<String, String> buildMergeMetadata(List<RetrievalResult> leaves, String windowContent) {
        Map<String, String> meta = new HashMap<>();
        meta.put("chunkLevel", "merged_parent");
        meta.put("mergedFrom", String.valueOf(leaves.size()));
        meta.put("leafChunkIds", leaves.stream()
                .map(RetrievalResult::chunkId)
                .collect(Collectors.joining(",")));
        if (windowContent != null) {
            meta.put("windowContent", windowContent);
        }
        return meta;
    }

    private Map<String, String> buildWindowMetadata(Map<String, String> originalMeta, String originalContent) {
        Map<String, String> meta = new HashMap<>(originalMeta);
        meta.put("windowApplied", "true");
        meta.put("originalContent", originalContent); // 保留原始内容供追溯
        return meta;
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    // Extended RetrievalResult with metadata map
    public record RetrievalResult(
            String chunkId,
            String content,
            double score,
            double relevance,
            Map<String, String> metadata  // 扩展：支持 metadata
    ) {
        public RetrievalResult(String chunkId, String content, double score, double relevance) {
            this(chunkId, content, score, relevance, new HashMap<>());
        }

        public RetrievalResult withMetadata(Map<String, String> meta) {
            return new RetrievalResult(chunkId, content, score, relevance, meta);
        }
    }
}
