package com.rag.api.rest.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * RAG 辅助接口（stub 实现）
 * 覆盖：sample-questions、settings、traces、v3/stop
 */
@RestController
@RequestMapping("/api/v1/rag")
public class RagController {

    // ---- sample-questions ----

    public record SampleQuestion(
            String id,
            String title,
            String description,
            String question,
            String createTime,
            String updateTime
    ) {}

    @GetMapping("/sample-questions")
    public ResponseEntity<List<SampleQuestion>> listSampleQuestions() {
        return ResponseEntity.ok(Collections.emptyList());
    }

    // ---- settings ----

    public record SystemSettings(
            UploadSettings upload,
            RagSettings rag,
            AiSettings ai
    ) {}
    public record UploadSettings(int maxFileSize, int maxRequestSize) {}
    public record Default(String collectionName, int dimension, String metricType) {}
    public record QueryRewrite(boolean enabled, int maxHistoryMessages, int maxHistoryChars) {}
    public record RateLimit(Map<String, Object> global) {}
    public record Memory(int historyKeepTurns, int summaryStartTurns, boolean summaryEnabled,
                         int ttlMinutes, int summaryMaxChars, int titleMaxLength) {}
    public record ModelCandidate(String id, String provider, String model, String url,
                                 Integer dimension, Integer priority, Boolean enabled, Boolean supportsThinking) {}
    public record ModelGroup(String defaultModel, String deepThinkingModel, List<ModelCandidate> candidates) {}
    public record AiSettings(Map<String, Map<String, Object>> providers,
                             AiSelection selection,
                             StreamSettings stream,
                             ModelGroup chat,
                             ModelGroup embedding,
                             ModelGroup rerank) {}
    public record AiSelection(int failureThreshold, int openDurationMs) {}
    public record StreamSettings(int messageChunkSize) {}

    public record RagSettings(
            @JsonProperty("default") Default defaultRag,
            QueryRewrite queryRewrite,
            RateLimit rateLimit,
            Memory memory
    ) {}

    @GetMapping("/settings")
    public ResponseEntity<SystemSettings> getSettings() {
        return ResponseEntity.ok(new SystemSettings(
                new UploadSettings(52428800, 104857600),
                new RagSettings(
                        new Default("rag_chunks", 1024, "COSINE"),
                        new QueryRewrite(true, 10, 2000),
                        new RateLimit(Map.of(
                                "enabled", false,
                                "maxConcurrent", 10,
                                "maxWaitSeconds", 30,
                                "leaseSeconds", 60,
                                "pollIntervalMs", 500
                        )),
                        new Memory(10, 5, true, 60, 500, 20)
                ),
                new AiSettings(
                        Map.of(
                                "minimax", Map.of(
                                        "url", "https://api.minimax.chat/v1",
                                        "apiKey", "",
                                        "endpoints", Map.of("chat", "/chat/completions", "embeddings", "/embeddings")
                                )
                        ),
                        new AiSelection(3, 300000),
                        new StreamSettings(50),
                        new ModelGroup("MiniMax-M2.7", null, List.of(
                                new ModelCandidate("MiniMax-M2.7", "minimax", "MiniMax-M2.7", null, null, 1, true, null)
                        )),
                        new ModelGroup("BAAI/bge-m3", null, List.of(
                                new ModelCandidate("BAAI/bge-m3", "minimax", "BAAI/bge-m3", null, 1024, 1, true, null)
                        )),
                        null
                )
        ));
    }

    // ---- traces ----

    public record PageResult<T>(List<T> records, long total, int size, int current, int pages) {}

    public record TraceRun(
            String traceId,
            String traceName,
            String entryMethod,
            String conversationId,
            String taskId,
            String username,
            String status,
            String errorMessage,
            Long durationMs,
            String startTime,
            String endTime
    ) {}

    @GetMapping("/traces/runs")
    public ResponseEntity<PageResult<TraceRun>> getTraceRuns(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(new PageResult<>(Collections.emptyList(), 0, size, current, 0));
    }

    @GetMapping("/traces/runs/{traceId}")
    public ResponseEntity<Map<String, Object>> getTraceDetail(@PathVariable String traceId) {
        return ResponseEntity.ok(Map.of(
                "run", Collections.emptyMap(),
                "nodes", Collections.emptyList()
        ));
    }

    @GetMapping("/traces/runs/{traceId}/nodes")
    public ResponseEntity<List<Map<String, Object>>> getTraceNodes(@PathVariable String traceId) {
        return ResponseEntity.ok(Collections.emptyList());
    }

    // ---- v3 stop ----

    @PostMapping("/v3/stop")
    public ResponseEntity<Void> stopTask(@RequestParam String taskId) {
        return ResponseEntity.ok().build();
    }
}
