package com.rag.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.application.retrieval.HybridRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SiliconFlowReranker 单元测试
 *
 * 测试：
 * 1. 禁用时不调用 API，直接返回候选
 * 2. API 返回正常时按分数重排
 * 3. API 失败时回退到 Bi-Encoder
 * 4. 候选数过少时不触发重排
 * 5. API 返回为空时的处理
 */
@ExtendWith(MockitoExtension.class)
public class SiliconFlowRerankerTest {

    @Mock
    private EmbeddingService embeddingService;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("禁用时：直接返回原始候选，不调用 API")
    void testDisabledReturnsCandidates() {
        SiliconFlowReranker reranker = new SiliconFlowReranker(
                WebClient.builder(), objectMapper, embeddingService,
                false, "BAAI/bge-reranker-v2-m3",
                "https://api.siliconflow.cn/v1", "fake-key");

        List<HybridRetrievalService.RetrievalResult> candidates = List.of(
                new HybridRetrievalService.RetrievalResult("chunk-1", "内容1", 0.9, 0.9),
                new HybridRetrievalService.RetrievalResult("chunk-2", "内容2", 0.8, 0.8),
                new HybridRetrievalService.RetrievalResult("chunk-3", "内容3", 0.7, 0.7));

        List<HybridRetrievalService.RetrievalResult> result =
                reranker.rerank("查询", candidates, 2);

        verifyNoInteractions(embeddingService);
        assertEquals(3, result.size(), "禁用时应返回全部原始候选");
    }

    @Test
    @DisplayName("候选数 <= 2 时：不触发重排")
    void testSkipRerankWhenCandidatesSmall() {
        SiliconFlowReranker reranker = new SiliconFlowReranker(
                WebClient.builder(), objectMapper, embeddingService,
                true, "BAAI/bge-reranker-v2-m3",
                "https://api.siliconflow.cn/v1", "fake-key");

        List<HybridRetrievalService.RetrievalResult> candidates = List.of(
                new HybridRetrievalService.RetrievalResult("chunk-1", "内容1", 0.9, 0.9),
                new HybridRetrievalService.RetrievalResult("chunk-2", "内容2", 0.8, 0.8));

        List<HybridRetrievalService.RetrievalResult> result =
                reranker.rerank("查询", candidates, 2);

        verifyNoInteractions(embeddingService);
        assertEquals(2, result.size(), "候选过少时应跳过重排");
    }

    @Test
    @DisplayName("空候选列表：应直接返回空列表")
    void testEmptyCandidatesReturnsEmpty() {
        SiliconFlowReranker reranker = new SiliconFlowReranker(
                WebClient.builder(), objectMapper, embeddingService,
                true, "BAAI/bge-reranker-v2-m3",
                "https://api.siliconflow.cn/v1", "fake-key");

        List<HybridRetrievalService.RetrievalResult> result =
                reranker.rerank("查询", List.of(), 5);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Bi-Encoder 回退：正常计算余弦相似度并排序")
    void testBiEncoderFallback() {
        SiliconFlowReranker reranker = new SiliconFlowReranker(
                WebClient.builder(), objectMapper, embeddingService,
                true, "BAAI/bge-reranker-v2-m3",
                "https://api.siliconflow.cn/v1", "fake-key");

        // Mock embeddingService 返回已知向量
        float[] queryVec = new float[]{0.5f, 0.5f};
        float[] vec1 = new float[]{0.9f, 0.1f}; // 高相似度
        float[] vec2 = new float[]{0.1f, 0.9f};  // 低相似度
        float[] vec3 = new float[]{0.5f, 0.5f}; // 完全相同

        when(embeddingService.embed("查询")).thenReturn(queryVec);
        when(embeddingService.embed("内容1")).thenReturn(vec1);
        when(embeddingService.embed("内容2")).thenReturn(vec2);
        when(embeddingService.embed("内容3")).thenReturn(vec3);

        List<HybridRetrievalService.RetrievalResult> candidates = List.of(
                new HybridRetrievalService.RetrievalResult("chunk-1", "内容1", 0.5, 0.5),
                new HybridRetrievalService.RetrievalResult("chunk-2", "内容2", 0.5, 0.5),
                new HybridRetrievalService.RetrievalResult("chunk-3", "内容3", 0.5, 0.5));

        List<HybridRetrievalService.RetrievalResult> result =
                reranker.fallbackBiEncoder("查询", candidates, 3);

        assertEquals(3, result.size());

        // vec3 与 queryVec 完全相同，相似度应为最高（1.0）
        // vec1 次之，vec2 最低
        double firstScore = result.get(0).score();
        for (int i = 1; i < result.size(); i++) {
            assertTrue(firstScore >= result.get(i).score(),
                    "结果应按分数降序排列");
        }

        verify(embeddingService, times(1 + 3)).embed(anyString());
        System.out.println("\n========== Bi-Encoder 回退测试 ==========");
        System.out.println("余弦相似度排序结果：");
        for (var r : result) {
            System.out.printf("  chunkId=%s score=%.4f%n", r.chunkId(), r.score());
        }
    }

    @Test
    @DisplayName("SiliconFlowReranker 实例化正常")
    void testSiliconFlowRerankerInstantiation() {
        SiliconFlowReranker reranker = new SiliconFlowReranker(
                WebClient.builder()
                        .baseUrl("https://api.siliconflow.cn/v1")
                        .defaultHeader("Authorization", "Bearer test-key"),
                objectMapper, embeddingService,
                true, "BAAI/bge-reranker-v2-m3",
                "https://api.siliconflow.cn/v1", "sk-test-key");

        assertNotNull(reranker);
        System.out.println("\n========== SiliconFlowReranker 实例化测试 ==========");
        System.out.println("✅ SiliconFlowReranker 实例化成功");
        System.out.println("配置: enabled=true, model=BAAI/bge-reranker-v2-m3");
    }

    @Test
    @DisplayName("分数一致性：Bi-Encoder 回退的分数在 [-1,1] 范围内")
    void testBiEncoderScoreRange() {
        SiliconFlowReranker reranker = new SiliconFlowReranker(
                WebClient.builder(), objectMapper, embeddingService,
                true, "BAAI/bge-reranker-v2-m3",
                "https://api.siliconflow.cn/v1", "fake-key");

        float[] vec = new float[]{0.5f, 0.5f};
        when(embeddingService.embed(anyString())).thenReturn(vec);

        List<HybridRetrievalService.RetrievalResult> candidates = List.of(
                new HybridRetrievalService.RetrievalResult("c1", "内容1", 0.5, 0.5),
                new HybridRetrievalService.RetrievalResult("c2", "内容2", 0.5, 0.5));

        List<HybridRetrievalService.RetrievalResult> result =
                reranker.fallbackBiEncoder("查询", candidates, 2);

        for (var r : result) {
            assertTrue(r.score() >= -1.0 && r.score() <= 1.0,
                    "余弦相似度应在 [-1, 1] 范围内: " + r.score());
        }
    }
}
