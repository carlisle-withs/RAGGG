package com.rag.infrastructure.llm;

import com.rag.config.AppConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StreamingChatModelService 传输层测试：用 JDK 内置 HttpServer 模拟 OpenAI 兼容 SSE 端点，
 * 验证 HttpClient 流式实现的 token 提取、reasoning_content 忽略、think 标签清理与错误传播。
 */
class StreamingChatModelServiceTest {

    private HttpServer server;
    private volatile String sseBody = "";
    private volatile int statusCode = 200;
    private StreamingChatModelService service;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = sseBody.getBytes(StandardCharsets.UTF_8);
            if (statusCode != 200) {
                exchange.sendResponseHeaders(statusCode, body.length);
            } else {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
            }
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        AppConfig config = new AppConfig();
        config.getLlm().setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/v1");
        config.getLlm().setApiKey("test-key");
        config.getLlm().setModel("test-model");
        service = new StreamingChatModelService(config);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void streamsDeltaContentInOrderAndCompletes() throws Exception {
        sseBody = """
                data: {"choices":[{"delta":{"content":"你好"}}]}

                data: {"choices":[{"delta":{"content":"，世界"}}]}

                data: [DONE]

                """;
        CollectingCallback callback = new CollectingCallback();

        String full = service.stream("hi", callback).get(5, TimeUnit.SECONDS);

        assertThat(callback.tokens).containsExactly("你好", "，世界");
        assertThat(full).isEqualTo("你好，世界");
        assertThat(callback.completed).isTrue();
        assertThat(callback.errors).isEmpty();
    }

    @Test
    void ignoresReasoningContentBlocks() throws Exception {
        sseBody = """
                data: {"choices":[{"delta":{"reasoning_content":"用户想打招呼"}}]}

                data: {"choices":[{"delta":{"content":"Hi"}}]}

                data: [DONE]

                """;
        CollectingCallback callback = new CollectingCallback();

        String full = service.stream("hi", callback).get(5, TimeUnit.SECONDS);

        assertThat(callback.tokens).containsExactly("Hi");
        assertThat(full).isEqualTo("Hi");
    }

    @Test
    void stripsThinkTagsFromFinalResponse() throws Exception {
        sseBody = """
                data: {"choices":[{"delta":{"content":"<think>推理过程</think>最终回答"}}]}

                data: [DONE]

                """;
        CollectingCallback callback = new CollectingCallback();

        String full = service.stream("hi", callback).get(5, TimeUnit.SECONDS);

        assertThat(full).isEqualTo("最终回答");
    }

    @Test
    void httpErrorPropagatesToOnError() {
        statusCode = 500;
        sseBody = "internal server error";
        CollectingCallback callback = new CollectingCallback();

        CompletableFuture<String> future = service.stream("hi", callback);

        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
        assertThat(callback.errors).hasSize(1);
        assertThat(callback.completed).isFalse();
    }

    private static class CollectingCallback implements StreamingChatModelService.StreamingCallback {
        final List<String> tokens = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        volatile boolean completed = false;

        @Override
        public void onNext(String token) {
            tokens.add(token);
        }

        @Override
        public void onComplete(String fullResponse) {
            completed = true;
        }

        @Override
        public void onError(Throwable e) {
            errors.add(e);
        }
    }
}
