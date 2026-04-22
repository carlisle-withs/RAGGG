package com.rag.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自定义 Micrometer 指标注册中心。
 * 所有埋点通过 MeterRegistry 获取或创建 Timer/Counter，
 * 无需手动声明 bean，由 Spring 自动注入。
 */
@Configuration
public class MetricsConfig {

    // ===== Upload 阶段 =====
    public static final String STAGE_UPLOAD = "doc.upload";

    // ===== Pipeline 各阶段 =====
    public static final String STAGE_PARSE    = "doc.pipeline.parse";
    public static final String STAGE_CHUNK     = "doc.pipeline.chunk";
    public static final String STAGE_EMBED     = "doc.pipeline.embed";
    public static final String STAGE_ES        = "doc.pipeline.elasticsearch";
    public static final String STAGE_MILVUS    = "doc.pipeline.milvus";
    public static final String STAGE_FULL_IDX  = "doc.pipeline.full_index";

    // Kafka topics
    public static final String TOPIC_UPLOAD  = "document-upload";
    public static final String TOPIC_PARSED  = "document-parsed";
    public static final String TOPIC_CHUNKED = "document-chunked";

    // --- Atomic holders for Gauges (active in-progress counts) ---
    private final AtomicInteger parseInProgress   = new AtomicInteger(0);
    private final AtomicInteger chunkInProgress   = new AtomicInteger(0);
    private final AtomicInteger indexInProgress   = new AtomicInteger(0);

    public MetricsConfig(MeterRegistry registry) {
        // 活跃处理中的文档数
        Gauge.builder("doc_pipeline_inprogress", parseInProgress, AtomicInteger::get)
                .tag("stage", "parse").description("Number of documents currently being parsed")
                .register(registry);
        Gauge.builder("doc_pipeline_inprogress", chunkInProgress, AtomicInteger::get)
                .tag("stage", "chunk").description("Number of documents currently being chunked")
                .register(registry);
        Gauge.builder("doc_pipeline_inprogress", indexInProgress, AtomicInteger::get)
                .tag("stage", "index").description("Number of documents currently being indexed")
                .register(registry);
    }

    // ---- Timer factory helpers ----

    public static Timer.Sample startTimer() {
        return Timer.start();
    }

    public Timer timer(MeterRegistry registry, String name) {
        return Timer.builder(name).register(registry);
    }

    // ---- Convenience counter helpers ----

    public static void incParseInProgress(AtomicInteger counter)  { counter.incrementAndGet(); }
    public static void decParseInProgress(AtomicInteger counter)  { counter.decrementAndGet(); }
    public static void incChunkInProgress(AtomicInteger counter) { counter.incrementAndGet(); }
    public static void decChunkInProgress(AtomicInteger counter) { counter.decrementAndGet(); }
    public static void incIndexInProgress(AtomicInteger counter)  { counter.incrementAndGet(); }
    public static void decIndexInProgress(AtomicInteger counter)  { counter.decrementAndGet(); }

    public AtomicInteger parseInProgress()   { return parseInProgress; }
    public AtomicInteger chunkInProgress()   { return chunkInProgress; }
    public AtomicInteger indexInProgress()   { return indexInProgress; }
}
