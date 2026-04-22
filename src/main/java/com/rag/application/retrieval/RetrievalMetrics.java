package com.rag.application.retrieval;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.DistributionSummary;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Retrieval 埋点指标
 *
 * 暴露给 Prometheus 的指标:
 *   retrieval_requests_total{mode}           - 各模式请求总数
 *   retrieval_latency_seconds{mode}          - 各模式延迟分布 (P50/P95/P99)
 *   retrieval_results_count{mode}            - 各模式返回结果数分布
 *   retrieval_rerank_latency_seconds{mode}   - 精排延迟分布
 *   retrieval_errors_total{mode,type}        - 各模式错误数
 */
@Component
public class RetrievalMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> requestCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> latencyTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DistributionSummary> resultCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> rerankTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> errorCounters = new ConcurrentHashMap<>();

    public RetrievalMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    private String modeKey(String mode) {
        return mode.toLowerCase();
    }

    public void recordRequest(String mode) {
        Counter counter = requestCounters.computeIfAbsent(modeKey(mode), m ->
                Counter.builder("retrieval_requests_total")
                        .description("Total retrieval requests")
                        .tag("mode", m)
                        .register(registry));
        counter.increment();
    }

    public void recordLatency(String mode, long durationMs) {
        Timer timer = latencyTimers.computeIfAbsent(modeKey(mode), m ->
                Timer.builder("retrieval_latency_seconds")
                        .description("Retrieval latency in seconds")
                        .tag("mode", m)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .publishPercentileHistogram()
                        .register(registry));
        timer.record(java.time.Duration.ofMillis(durationMs));
    }

    public void recordResultCount(String mode, int count) {
        DistributionSummary summary = resultCounters.computeIfAbsent(modeKey(mode), m ->
                DistributionSummary.builder("retrieval_results_count")
                        .description("Number of retrieval results")
                        .tag("mode", m)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry));
        summary.record(count);
    }

    public void recordRerankLatency(String mode, long durationMs) {
        Timer timer = rerankTimers.computeIfAbsent(modeKey(mode), m ->
                Timer.builder("retrieval_rerank_latency_seconds")
                        .description("Rerank latency in seconds")
                        .tag("mode", m)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .publishPercentileHistogram()
                        .register(registry));
        timer.record(java.time.Duration.ofMillis(durationMs));
    }

    public void recordError(String mode, String errorType) {
        String key = modeKey(mode) + "_" + errorType;
        Counter counter = errorCounters.computeIfAbsent(key, k ->
                Counter.builder("retrieval_errors_total")
                        .description("Total retrieval errors")
                        .tag("mode", modeKey(mode))
                        .tag("type", errorType)
                        .register(registry));
        counter.increment();
    }
}
