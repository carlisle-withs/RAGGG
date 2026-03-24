package com.rag.api.rest.chat;

import com.rag.application.chat.ChatApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private final ChatApplicationService chatService;

    public ChatController(ChatApplicationService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        if (request.message() == null || request.message().trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        String conversationId = request.conversationId();
        if (conversationId == null || conversationId.isEmpty()) {
            conversationId = UUID.randomUUID().toString();
        }

        String kbId = (request.kbIds() != null && !request.kbIds().isEmpty())
                ? request.kbIds().get(0)
                : null;

        ChatApplicationService.ChatResponse response = chatService.chat(request.message(), kbId);

        return ResponseEntity.ok(new ChatResponse(
                conversationId,
                response.message(),
                response.sources().stream()
                        .map(s -> new Source(s.chunkId(), s.content(), s.score()))
                        .toList(),
                null,
                null,
                null
        ));
    }

    public record ChatRequest(String message, String conversationId, String userId, List<String> kbIds, Boolean stream) {}

    public record ChatResponse(String conversationId, String message, List<Source> sources,
                              Map<String, Object> tokens, String intent, List<String> routedKbIds) {}

    public record Source(String chunkId, String content, double score) {}
}
