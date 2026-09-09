package com.rag.application.chat;

import com.rag.application.retrieval.RetrievalApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统一检索管线测试：指代消解的触发条件、查询扩展回退、检索失败降级。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagContextServiceTest {

    @Mock
    private QueryRewriter queryRewriter;

    @Mock
    private RetrievalApplicationService retrievalService;

    @InjectMocks
    private RagContextService service;

    private RetrievalApplicationService.RetrievalResult result(String id, String content) {
        return new RetrievalApplicationService.RetrievalResult(id, content, 0.9, 0.9);
    }

    @Test
    void appliesCondenseThenExpandWhenMemoryPresent() {
        when(queryRewriter.condenseWithMemory("那它怎么配置", "【对话历史】..."))
                .thenReturn("Qwen3-VL-Embedding 怎么配置");
        when(queryRewriter.expand("Qwen3-VL-Embedding 怎么配置"))
                .thenReturn("Qwen3-VL-Embedding 配置方法 部署");
        when(retrievalService.hybridSearch("Qwen3-VL-Embedding 配置方法 部署", "kb1", 5, true))
                .thenReturn(List.of(result("c1", "配置步骤...")));

        RagContextService.RagContext ctx = service.build("那它怎么配置", "【对话历史】...", "kb1");

        assertThat(ctx.searchQuery()).isEqualTo("Qwen3-VL-Embedding 配置方法 部署");
        assertThat(ctx.sources()).hasSize(1);
        assertThat(ctx.contextText()).contains("【文档】配置步骤...");
    }

    @Test
    void skipsCondenseWithoutMemory() {
        when(queryRewriter.expand("单轮问题")).thenReturn("扩展后的问题");
        when(retrievalService.hybridSearch(anyString(), eq("kb1"), eq(5), eq(true)))
                .thenReturn(List.of());

        RagContextService.RagContext ctx = service.build("单轮问题", "", "kb1");

        verify(queryRewriter, never()).condenseWithMemory(anyString(), anyString());
        verify(retrievalService).hybridSearch("扩展后的问题", "kb1", 5, true);
        assertThat(ctx.sources()).isEmpty();
        assertThat(ctx.contextText()).isEmpty();
    }

    @Test
    void skipsRetrievalWithoutKbId() {
        service.build("你好", "", null);

        verify(queryRewriter, never()).condenseWithMemory(anyString(), anyString());
        verify(retrievalService, never()).hybridSearch(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void retrievalFailureDegradesToEmptySources() {
        when(queryRewriter.expand("问题")).thenReturn("问题");
        when(retrievalService.hybridSearch(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenThrow(new RuntimeException("milvus down"));

        RagContextService.RagContext ctx = service.build("问题", "", "kb1");

        assertThat(ctx.sources()).isEmpty();
        assertThat(ctx.contextText()).isEmpty();
    }

    @Test
    void expansionFailureFallsBackToCondensedQuery() {
        when(queryRewriter.condenseWithMemory("追问", "记忆")).thenReturn("独立问题");
        when(queryRewriter.expand("独立问题")).thenThrow(new RuntimeException("llm timeout"));
        when(retrievalService.hybridSearch(eq("独立问题"), eq("kb1"), eq(5), eq(true)))
                .thenReturn(List.of(result("c1", "内容")));

        RagContextService.RagContext ctx = service.build("追问", "记忆", "kb1");

        assertThat(ctx.searchQuery()).isEqualTo("独立问题");
        assertThat(ctx.sources()).hasSize(1);
    }
}
