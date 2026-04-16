package com.rag.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.logging.LoggingMetricExporter;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * OpenTelemetry 配置
 *
 * 提供 Tracing + Metrics 能力：
 * - Tracer: 全链路 Span，支持接入 Jaeger/Zipkin
 * - Meter: 业务 Metrics，支持 Prometheus 抓取
 *
 * 启用条件：otel.enabled=true
 */
@Configuration
@ConditionalOnProperty(name = "otel.enabled", havingValue = "true", matchIfMissing = false)
public class OpenTelemetryConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenTelemetryConfig.class);

    @Value("${otel.service.name:raggg}")
    private String serviceName;

    @Value("${otel.service.version:1.0.0}")
    private String serviceVersion;

    @Value("${otel.exporter.endpoint:http://localhost:4317}")
    private String exporterEndpoint;

    @Value("${otel.metrics.interval-seconds:30}")
    private int metricsIntervalSeconds;

    @Value("${otel.resource.env:dev}")
    private String env;

    @Bean
    public OpenTelemetry openTelemetry() {
        // Resource — 服务标识
        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), serviceName,
                        AttributeKey.stringKey("service.version"), serviceVersion,
                        AttributeKey.stringKey("deployment.environment"), env
                )));

        // 判断是否使用 OTLP 导出器
        boolean useOtlp = exporterEndpoint != null
                && !exporterEndpoint.startsWith("console")
                && !exporterEndpoint.isEmpty();

        // SpanExporter
        var spanExporter = useOtlp
                ? OtlpGrpcSpanExporter.builder()
                    .setEndpoint(exporterEndpoint)
                    .setTimeout(Duration.ofSeconds(10))
                    .build()
                : LoggingSpanExporter.create();

        // MetricExporter
        var metricExporter = useOtlp
                ? OtlpGrpcMetricExporter.builder()
                    .setEndpoint(exporterEndpoint)
                    .setTimeout(Duration.ofSeconds(10))
                    .build()
                : LoggingMetricExporter.create();

        // TracerProvider
        SdkTracerProvider tracerProvider;
        if (useOtlp) {
            tracerProvider = SdkTracerProvider.builder()
                    .setResource(resource)
                    .addSpanProcessor(BatchSpanProcessor.builder(spanExporter)
                            .setMaxQueueSize(2048)
                            .setMaxExportBatchSize(512)
                            .setScheduleDelay(Duration.ofMillis(100))
                            .build())
                    .build();
        } else {
            tracerProvider = SdkTracerProvider.builder()
                    .setResource(resource)
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build();
        }

        // MeterProvider
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(
                        PeriodicMetricReader.builder(metricExporter)
                                .setInterval(Duration.ofSeconds(metricsIntervalSeconds))
                                .build()
                )
                .build();

        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .build();

        log.info("[OpenTelemetry] Initialized: service={}, version={}, endpoint={}, useOtlp={}",
                serviceName, serviceVersion, exporterEndpoint, useOtlp);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[OpenTelemetry] Shutting down...");
            tracerProvider.close();
            meterProvider.close();
        }));

        return openTelemetry;
    }

    @Bean
    public Tracer ragTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("raggg.retrieval", "1.0.0");
    }

    @Bean
    public Tracer chatTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("raggg.chat", "1.0.0");
    }

    @Bean
    public Tracer documentTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("raggg.document", "1.0.0");
    }

    @Bean
    public Meter ragMeter(OpenTelemetry openTelemetry) {
        return openTelemetry.getMeter("raggg");
    }
}
