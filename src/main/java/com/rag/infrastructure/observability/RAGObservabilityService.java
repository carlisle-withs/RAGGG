package com.rag.infrastructure.observability;

import io.micrometer.core.instrument.*;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RAG 可观测性服务
 *
 * 使用 Micrometer（Spring Boot 标准）进行 Metrics 采集，
 * 通过 MicrometerBridge 转发到 Prometheus。
 *
 * 提供三大能力：
 * 1. Tracing — Span 级延迟追踪（覆盖检索全链路各子环节）
 * 2. Metrics — 业务指标采集（Prometheus 格式）
 * 3. Logging — 结构化事件记录
 */
@Service
@ConditionalOnProperty(name = "otel.enabled", havingValue = "true", matchIfMissing = false)
public class RAGObservabilityService {

    private static final Logger log = LoggerFactory.getLogger(RAGObservabilityService.class);

    private final Tracer retrievalTracer;
    private final Tracer chatTracer;
    private final Meter meter;
    private final MeterRegistry meterRegistry;

    // Metrics 组件
    private Counter retrievalTotalCounter;
    private Counter retrievalSuccessCounter;
    private Counter retrievalEmptyCounter;
    private Counter rerankApiFailureCounter;
    private Counter llmApiFailureCounter;
    private Timer retrievalLatencyTimer;
    private Timer rerankLatencyTimer;
    private Timer llmLatencyTimer;
    private DistributionSummary retrievedChunksSummary;
    private AtomicLong activeConversations = new AtomicLong(0);

    public RAGObservabilityService(
            @Autowired(required = false) Tracer retrievalTracer,
            @Autowired(required = false) Tracer chatTracer,
            Meter meter,
            MeterRegistry meterRegistry) {
        this.retrievalTracer = retrievalTracer != null ? retrievalTracer : OpenTelemetry.noop().getTracer("noop");
        this.chatTracer = chatTracer != null ? chatTracer : OpenTelemetry.noop().getTracer("noop");
        this.meter = meter;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void initMetrics() {
        if (meterRegistry == null) {
            log.warn("[RAGObservability] MeterRegistry not available, metrics disabled");
            return;
        }

        try {
            retrievalTotalCounter = meterRegistry.counter("rag.retrieval.total");
            retrievalSuccessCounter = meterRegistry.counter("rag.retrieval.success");
            retrievalEmptyCounter = meterRegistry.counter("rag.retrieval.empty");
            retrievalLatencyTimer = meterRegistry.timer("rag.retrieval.latency");
            rerankApiFailureCounter = meterRegistry.counter("rag.rerank.api.failure");
            rerankLatencyTimer = meterRegistry.timer("rag.rerank.latency");
            llmApiFailureCounter = meterRegistry.counter("rag.llm.api.failure");
            llmLatencyTimer = meterRegistry.timer("rag.llm.generation.latency");
            retrievedChunksSummary = meterRegistry.summary("rag.retrieved.chunks.count");
            meterRegistry.gauge("rag.conversations.active", activeConversations);

            log.info("[RAGObservability] All metrics initialized via Micrometer");
        } catch (Exception e) {
            log.error("[RAGObservability] Failed to initialize metrics: {}", e.getMessage());
        }
    }

    // ===== Span 工具方法 =====

    public <T> T recordSpan(String spanName, SpanKind kind, SpanOperation<T> operation) {
        Span span = retrievalTracer.spanBuilder(spanName)
                .setSpanKind(kind)
                .startSpan();

        long startTime = System.currentTimeMillis();
        try (Scope scope = span.makeCurrent()) {
            T result = operation.execute();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Throwable t) {
            span.recordException(t);
            span.setStatus(StatusCode.ERROR, t.getMessage());
            throw t;
        } finally {
            span.setAttribute("duration_ms", System.currentTimeMillis() - startTime);
            span.end();
        }
    }

    public void recordSpan(String spanName, SpanKind kind, RunnableSpanOperation operation) {
        Span span = retrievalTracer.spanBuilder(spanName)
                .setSpanKind(kind)
                .startSpan();

        long startTime = System.currentTimeMillis();
        try (Scope scope = span.makeCurrent()) {
            operation.execute();
            span.setStatus(StatusCode.OK);
        } catch (Throwable t) {
            span.recordException(t);
            span.setStatus(StatusCode.ERROR, t.getMessage());
        } finally {
            span.setAttribute("duration_ms", System.currentTimeMillis() - startTime);
            span.end();
        }
    }

    // ===== 业务事件记录 =====

    public void recordRetrieval(RetrievalContext ctx) {
        if (retrievalTotalCounter != null) retrievalTotalCounter.increment();
        if (retrievalLatencyTimer != null) {
            retrievalLatencyTimer.record(Duration.ofMillis(ctx.totalLatencyMs));
        }
        if (ctx.finalCount > 0) {
            if (retrievalSuccessCounter != null) retrievalSuccessCounter.increment();
        } else {
            if (retrievalEmptyCounter != null) retrievalEmptyCounter.increment();
        }
        if (retrievedChunksSummary != null) {
            retrievedChunksSummary.record(ctx.candidatesCount);
        }

        Span span = retrievalTracer.spanBuilder("rag.retrieval")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("rag.query.length", ctx.queryLength);
            span.setAttribute("rag.kb.id", ctx.kbId);
            span.setAttribute("rag.conversation.id", ctx.conversationId);
            span.setAttribute("rag.intent", ctx.intent);
            span.setAttribute("rag.candidates.count", ctx.candidatesCount);
            span.setAttribute("rag.final.count", ctx.finalCount);
            span.setAttribute("rag.rerank.enabled", ctx.rerankEnabled);
            span.setAttribute("rag.retrieval.latency_ms", ctx.totalLatencyMs);
            span.setAttribute("rag.milvus.latency_ms", ctx.milvusLatencyMs);
            span.setAttribute("rag.es.latency_ms", ctx.esLatencyMs);
            span.setAttribute("rag.rerank.latency_ms", ctx.rerankLatencyMs);
            span.setAttribute("rag.strategy", ctx.retrievalStrategy);
            span.setStatus(StatusCode.OK);
        } finally {
            span.end();
        }

        log.info("[RAG] retrieval queryLen={} kb={} intent={} candidates={} final={} latency={}ms strategy={}",
                ctx.queryLength, ctx.kbId, ctx.intent, ctx.candidatesCount,
                ctx.finalCount, ctx.totalLatencyMs, ctx.retrievalStrategy);
    }

    public void recordLLMGeneration(LLMContext ctx) {
        if (llmLatencyTimer != null) {
            llmLatencyTimer.record(Duration.ofMillis(ctx.latencyMs));
        }
        if (!ctx.success && llmApiFailureCounter != null) {
            llmApiFailureCounter.increment();
        }

        Span span = chatTracer.spanBuilder("rag.llm.generate")
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("rag.llm.provider", ctx.provider);
            span.setAttribute("rag.llm.model", ctx.model);
            span.setAttribute("rag.prompt.tokens", ctx.promptTokens);
            span.setAttribute("rag.completion.tokens", ctx.completionTokens);
            span.setAttribute("rag.total.tokens", ctx.totalTokens);
            span.setAttribute("rag.llm.latency_ms", ctx.latencyMs);
            span.setAttribute("rag.llm.first_token_latency_ms", ctx.firstTokenLatencyMs);
            span.setAttribute("rag.llm.success", ctx.success);
            span.setStatus(ctx.success ? StatusCode.OK : StatusCode.ERROR);
        } finally {
            span.end();
        }
    }

    public void recordRerankFailure(String reason, int candidateCount) {
        if (rerankApiFailureCounter != null) rerankApiFailureCounter.increment();
        log.warn("[RAG] Rerank API failed: reason={}, candidates={}", reason, candidateCount);
    }

    public void recordRerankLatency(long latencyMs, int candidateCount, boolean success) {
        if (rerankLatencyTimer != null) {
            rerankLatencyTimer.record(Duration.ofMillis(latencyMs));
        }
        if (!success && rerankApiFailureCounter != null) {
            rerankApiFailureCounter.increment();
        }
        log.debug("[RAG] Rerank latency={}ms candidates={} success={}",
                latencyMs, candidateCount, success);
    }

    public void incrementActiveConversations() { activeConversations.incrementAndGet(); }
    public void decrementActiveConversations() {
        long current = activeConversations.decrementAndGet();
        if (current < 0) activeConversations.set(0);
    }

    // ===== 函数式接口 =====
    @FunctionalInterface
    public interface SpanOperation<T> { T execute(); }
    @FunctionalInterface
    public interface RunnableSpanOperation { void execute(); }

    // ===== Context DTOs =====
    @lombok.Builder @lombok.Data
    public static class RetrievalContext {
        private int queryLength;
        private String kbId;
        private String conversationId;
        private String intent;
        private String retrievalStrategy;
        private int candidatesCount;
        private int finalCount;
        private boolean rerankEnabled;
        private long totalLatencyMs;
        private long milvusLatencyMs;
        private long esLatencyMs;
        private long rerankLatencyMs;
    }

    @lombok.Builder @lombok.Data
    public static class LLMContext {
        private String provider;
        private String model;
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
        private long latencyMs;
        private long firstTokenLatencyMs;
        private boolean success;
    }
}
