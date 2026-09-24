package com.rag.application.retrieval;

import com.rag.infrastructure.llm.EmbeddingService;
import com.rag.infrastructure.search.ElasticsearchSearch;
import com.rag.infrastructure.vector.MilvusVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 层级检索（SWA）行为级回归测试。
 *
 * 背景：此前 HybridRetrievalService.RetrievalResult 不携带 metadata，
 * HierarchicalRetrievalService 拿到的 parentChunkId/siblingCount/windowContent 恒为空，
 * 导致 Auto-Merging 永不合并、Sentence Window 永不扩展（SWA 端到端失效，known-gaps #SWA）。
 * 本测试锁定修复：metadata 必须从召回层透传到层级检索层，且合并/扩展逻辑真实发生。
 */
class HierarchicalRetrievalServiceTest {

    private HybridRetrievalService hybridRetrievalService;
    private HierarchicalRetrievalService service;

    @BeforeEach
    void setUp() {
        hybridRetrievalService = mock(HybridRetrievalService.class);
        service = new HierarchicalRetrievalService(
                mock(MilvusVectorStore.class),
                mock(ElasticsearchSearch.class),
                mock(EmbeddingService.class),
                hybridRetrievalService,
                Runnable::run,
                0.5,   // merging-ratio
                3      // window-size
        );
    }

    private Map<String, String> leafMeta(String parentChunkId, int siblingCount, String windowContent) {
        Map<String, String> meta = new HashMap<>();
        meta.put("chunkLevel", "leaf");
        if (parentChunkId != null) meta.put("parentChunkId", parentChunkId);
        meta.put("siblingCount", String.valueOf(siblingCount));
        if (windowContent != null) meta.put("windowContent", windowContent);
        return meta;
    }

    @Test
    @DisplayName("metadata 透通后：同一 Parent 命中率达标时 Auto-Merging 合并回父块")
    void autoMergesLeavesIntoParentWhenHitRatioSufficient() {
        // 4 个兄弟叶子中命中 3 个 → hitRatio = 0.75 ≥ 0.5，且 ≥ minLeavesToMerge(2)
        when(hybridRetrievalService.hybridSearch(anyString(), anyString(), anyInt())).thenReturn(List.of(
                new HybridRetrievalService.RetrievalResult("l1", "句子一", 0.03, 0.03, leafMeta("p1", 4, null)),
                new HybridRetrievalService.RetrievalResult("l2", "句子二", 0.03, 0.03, leafMeta("p1", 4, null)),
                new HybridRetrievalService.RetrievalResult("l3", "句子三", 0.03, 0.03, leafMeta("p1", 4, null))
        ));

        List<HierarchicalRetrievalService.RetrievalResult> results = service.retrieve("查询", "kb1", 5);

        assertEquals(1, results.size(), "3 个叶子应合并为 1 个父块");
        HierarchicalRetrievalService.RetrievalResult merged = results.get(0);
        assertEquals("p1", merged.chunkId());
        assertEquals("merged_parent", merged.metadata().get("chunkLevel"));
        assertEquals("3", merged.metadata().get("mergedFrom"));
        assertTrue(merged.content().contains("句子一"));
        assertTrue(merged.content().contains("句子二"));
        assertTrue(merged.content().contains("句子三"));
    }

    @Test
    @DisplayName("metadata 透通后：命中率不足时保留叶子并应用 Sentence Window 扩展")
    void keepsLeafAndExpandsWindowWhenHitRatioInsufficient() {
        // 4 个兄弟叶子只命中 1 个 → hitRatio = 0.25 < 0.5，不合并；叶子应被 windowContent 替换
        when(hybridRetrievalService.hybridSearch(anyString(), anyString(), anyInt())).thenReturn(List.of(
                new HybridRetrievalService.RetrievalResult("l1", "单个句子", 0.03, 0.03,
                        leafMeta("p1", 4, "前一句。 单个句子。 后一句。"))
        ));

        List<HierarchicalRetrievalService.RetrievalResult> results = service.retrieve("查询", "kb1", 5);

        assertEquals(1, results.size());
        HierarchicalRetrievalService.RetrievalResult leaf = results.get(0);
        assertEquals("l1", leaf.chunkId(), "不应合并（hitRatio 不足）");
        assertEquals("前一句。 单个句子。 后一句。", leaf.content(), "内容应被 windowContent 替换");
        assertEquals("true", leaf.metadata().get("windowApplied"));
        assertEquals("单个句子", leaf.metadata().get("originalContent"), "原内容应存入 metadata 供追溯");
    }

    @Test
    @DisplayName("无 parentId 的孤立 chunk 原样保留，不参与合并")
    void keepsOrphansUnchanged() {
        when(hybridRetrievalService.hybridSearch(anyString(), anyString(), anyInt())).thenReturn(List.of(
                new HybridRetrievalService.RetrievalResult("orphan1", "孤立句子", 0.02, 0.02, leafMeta(null, 1, null))
        ));

        List<HierarchicalRetrievalService.RetrievalResult> results = service.retrieve("查询", "kb1", 5);

        assertEquals(1, results.size());
        assertEquals("orphan1", results.get(0).chunkId());
        assertEquals("孤立句子", results.get(0).content());
    }

    @Test
    @DisplayName("回归守卫：召回结果无 metadata 时不崩溃，退化为直通（旧行为基线）")
    void degradesGracefullyWithoutMetadata() {
        when(hybridRetrievalService.hybridSearch(anyString(), anyString(), anyInt())).thenReturn(List.of(
                new HybridRetrievalService.RetrievalResult("c1", "普通内容", 0.02, 0.02),
                new HybridRetrievalService.RetrievalResult("c2", "普通内容二", 0.01, 0.01)
        ));

        List<HierarchicalRetrievalService.RetrievalResult> results = service.retrieve("查询", "kb1", 5);

        assertEquals(2, results.size(), "无 metadata 的候选应原样返回");
        assertEquals("c1", results.get(0).chunkId());
    }
}
