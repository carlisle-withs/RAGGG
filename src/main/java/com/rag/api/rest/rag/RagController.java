package com.rag.api.rest.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rag.domain.model.RagTraceNode;
import com.rag.domain.model.RagTraceRun;
import com.rag.domain.repository.RagTraceNodeRepository;
import com.rag.domain.repository.RagTraceRunRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * RAG 辅助接口
 * 覆盖：sample-questions（stub）、settings（静态视图）、traces（真实数据）、v3/stop（stub）
 */
@RestController
@RequestMapping("/api/v1/rag")
public class RagController {

    private final RagTraceRunRepository traceRunRepository;
    private final RagTraceNodeRepository traceNodeRepository;

    public RagController(RagTraceRunRepository traceRunRepository,
                         RagTraceNodeRepository traceNodeRepository) {
        this.traceRunRepository = traceRunRepository;
        this.traceNodeRepository = traceNodeRepository;
    }

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
    public record UploadSettings(long maxFileSize, long maxRequestSize) {}
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
                new UploadSettings(2_147_483_648L, 2_147_483_648L),
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
                                "deepseek", Map.of(
                                        "url", "https://api.deepseek.com/v1",
                                        "apiKey", "",
                                        "endpoints", Map.of("chat", "/chat/completions")
                                ),
                                "siliconflow", Map.of(
                                        "url", "https://api.siliconflow.cn/v1",
                                        "apiKey", "",
                                        "endpoints", Map.of("chat", "/chat/completions", "embeddings", "/embeddings", "rerank", "/rerank")
                                ),
                                "ollama", Map.of(
                                        "url", "http://localhost:11434",
                                        "apiKey", "",
                                        "endpoints", Map.of("embeddings", "/api/embeddings")
                                )
                        ),
                        new AiSelection(3, 300000),
                        new StreamSettings(50),
                        new ModelGroup("deepseek-chat", null, List.of(
                                new ModelCandidate("deepseek-chat", "deepseek", "deepseek-chat", null, null, 1, true, null)
                        )),
                        new ModelGroup("BAAI/bge-m3", null, List.of(
                                new ModelCandidate("bge-m3:latest", "ollama", "bge-m3:latest", null, 1024, 2, true, null),
                                new ModelCandidate("BAAI/bge-m3", "siliconflow", "BAAI/bge-m3", null, 1024, 1, true, null)
                        )),
                        new ModelGroup("BAAI/bge-reranker-v2-m3", null, List.of(
                                new ModelCandidate("BAAI/bge-reranker-v2-m3", "siliconflow", "BAAI/bge-reranker-v2-m3", null, null, 1, true, null)
                        ))
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
        PageRequest pageable = PageRequest.of(Math.max(0, current - 1), Math.max(1, size),
                Sort.by(Sort.Direction.DESC, "createTime"));
        Page<RagTraceRun> page = traceRunRepository.search(
                blankToNull(traceId), blankToNull(conversationId),
                blankToNull(taskId), blankToNull(status), pageable);
        List<TraceRun> records = page.getContent().stream().map(this::toTraceRun).toList();
        return ResponseEntity.ok(new PageResult<>(records, page.getTotalElements(),
                page.getSize(), current, page.getTotalPages()));
    }

    @GetMapping("/traces/runs/{traceId}")
    public ResponseEntity<Map<String, Object>> getTraceDetail(@PathVariable String traceId) {
        return traceRunRepository.findByTraceIdAndDeletedFalse(traceId)
                .<ResponseEntity<Map<String, Object>>>map(run -> ResponseEntity.ok(Map.of(
                        "run", toTraceRun(run),
                        "nodes", traceNodeRepository
                                .findByTraceIdAndDeletedFalseOrderByStartTimeAsc(traceId)
                                .stream().map(this::toTraceNode).toList()
                )))
                .orElseGet(() -> ResponseEntity.ok(Map.of(
                        "run", Collections.emptyMap(),
                        "nodes", Collections.emptyList()
                )));
    }

    @GetMapping("/traces/runs/{traceId}/nodes")
    public ResponseEntity<List<Map<String, Object>>> getTraceNodes(@PathVariable String traceId) {
        List<Map<String, Object>> nodes = traceNodeRepository
                .findByTraceIdAndDeletedFalseOrderByStartTimeAsc(traceId)
                .stream().map(this::toTraceNode)
                .toList();
        return ResponseEntity.ok(nodes);
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private TraceRun toTraceRun(RagTraceRun r) {
        return new TraceRun(
                r.getTraceId(), r.getTraceName(), r.getEntryMethod(),
                r.getConversationId(), r.getTaskId(), r.getUserId(),
                r.getStatus(), r.getErrorMessage(), r.getDurationMs(),
                r.getStartTime() != null ? r.getStartTime().toString() : null,
                r.getEndTime() != null ? r.getEndTime().toString() : null
        );
    }

    private Map<String, Object> toTraceNode(RagTraceNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("traceId", n.getTraceId());
        m.put("nodeId", n.getNodeId());
        m.put("parentNodeId", n.getParentNodeId());
        m.put("depth", n.getDepth());
        m.put("nodeType", n.getNodeType());
        m.put("nodeName", n.getNodeName());
        m.put("className", n.getClassName());
        m.put("methodName", n.getMethodName());
        m.put("status", n.getStatus());
        m.put("errorMessage", n.getErrorMessage());
        m.put("durationMs", n.getDurationMs());
        m.put("startTime", n.getStartTime() != null ? n.getStartTime().toString() : null);
        m.put("endTime", n.getEndTime() != null ? n.getEndTime().toString() : null);
        m.put("extraData", n.getExtraData());
        return m;
    }

    // ---- v3 stop ----

    @PostMapping("/v3/stop")
    public ResponseEntity<Void> stopTask(@RequestParam String taskId) {
        return ResponseEntity.ok().build();
    }
}
