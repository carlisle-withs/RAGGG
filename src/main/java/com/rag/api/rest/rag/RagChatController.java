package com.rag.api.rest.rag;

import com.rag.application.chat.MemoryService;
import com.rag.application.chat.QueryRewriter;
import com.rag.application.retrieval.RetrievalApplicationService;
import com.rag.infrastructure.llm.StreamingChatModelService;
import com.rag.infrastructure.llm.StreamingChatModelService.StreamingCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * RAG V3 Chat 接口（代理到已有的 SSE 流式聊天实现）
 * 前端 /api/v1/rag/v3/chat → 本接口
 */
@RestController
@RequestMapping("/api/v1/rag/v3")
public class RagChatController {

    private static final Logger log = LoggerFactory.getLogger(RagChatController.class);
    private static final long SSE_TIMEOUT = 300_000L;

    private final StreamingChatModelService streamingChatModel;
    private final RetrievalApplicationService retrievalService;
    private final MemoryService memoryService;
    private final QueryRewriter queryRewriter;
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    public RagChatController(
            StreamingChatModelService streamingChatModel,
            RetrievalApplicationService retrievalService,
            MemoryService memoryService,
            QueryRewriter queryRewriter) {
        this.streamingChatModel = streamingChatModel;
        this.retrievalService = retrievalService;
        this.memoryService = memoryService;
        this.queryRewriter = queryRewriter;
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chatStream(
            @RequestParam("question") String question,
            @RequestParam(value = "conversationId", required = false) String conversationId,
            @RequestParam(value = "kbId", required = false) String kbId) {

        if (question == null || question.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        if (conversationId == null || conversationId.isEmpty()) {
            conversationId = UUID.randomUUID().toString();
        }

        final String finalConversationId = conversationId;
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        sseExecutor.execute(() -> doStream(emitter, question.trim(), kbId, finalConversationId));
        return ResponseEntity.ok(emitter);
    }

    private void doStream(SseEmitter emitter, String question, String kbId, String conversationId) {
        try {
            sendEvent(emitter, "meta", String.format("{\"conversationId\":\"%s\"}", conversationId));

            String prompt = buildPrompt(question, kbId);
            StringBuilder fullResponse = new StringBuilder();

            CompletableFuture<String> future = streamingChatModel.stream(prompt,
                new StreamingCallback() {
                    @Override
                    public void onNext(String token) {
                        fullResponse.append(token);
                        sendEvent(emitter, "message",
                            String.format("{\"type\":\"response\",\"delta\":\"%s\"}", escapeJson(token)));
                    }

                    @Override
                    public void onComplete(String completeResponse) {
                        sendEvent(emitter, "finish", String.format(
                            "{\"conversationId\":\"%s\",\"title\":\"%s\"}",
                            conversationId, truncate(completeResponse, 20)));
                        sendEvent(emitter, "done", "");
                        completeEmitter(emitter);
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("RAG V3 streaming error", error);
                        sendEvent(emitter, "error",
                            String.format("{\"error\":\"%s\"}", error.getMessage()));
                        completeEmitter(emitter);
                    }
                });

            future.join();

        } catch (Exception e) {
            log.error("RAG V3 chat stream error", e);
            sendEvent(emitter, "error", String.format("{\"error\":\"%s\"}", e.getMessage()));
            completeEmitter(emitter);
        }
    }

    private String buildPrompt(String question, String kbId) {
        StringBuilder prompt = new StringBuilder();

        List<RetrievalApplicationService.RetrievalResult> sources = null;
        if (kbId != null && !kbId.isEmpty()) {
            try {
                String expandedQuery = queryRewriter.expand(question);
                sources = retrievalService.hybridSearch(expandedQuery, kbId, 5, true);
            } catch (Exception e) {
                log.warn("Failed to retrieve sources", e);
            }
        }

        if (sources != null && !sources.isEmpty()) {
            String ragContext = sources.stream()
                    .map(s -> "【文档】" + s.content())
                    .collect(Collectors.joining("\n\n"));
            prompt.append("【参考文档】\n").append(ragContext).append("\n\n");
            prompt.append("【当前问题】\n").append(question).append("\n\n");
            prompt.append("请基于参考文档回答当前问题。如果参考文档中没有相关信息，请明确说明。");
        } else {
            prompt.append("【当前问题】\n").append(question);
        }

        return prompt.toString();
    }

    private void sendEvent(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            log.debug("SSE send failed, client may have disconnected", e);
        }
    }

    private void completeEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE complete failed", e);
        }
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}
