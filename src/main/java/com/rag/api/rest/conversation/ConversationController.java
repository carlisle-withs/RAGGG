package com.rag.api.rest.conversation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 对话会话管理接口（stub 实现，返回空数据）
 * 前端需要此接口来加载会话列表和消息
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    public record ConversationVO(
            String conversationId,
            String title,
            String lastTime
    ) {}

    public record MessageVO(
            Object id,
            String conversationId,
            String role,
            String content,
            Object thinkingContent,
            Object thinkingDuration,
            Object vote,
            String createTime
    ) {}

    @GetMapping
    public ResponseEntity<List<ConversationVO>> listConversations() {
        return ResponseEntity.ok(Collections.emptyList());
    }

    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Void> deleteConversation(@PathVariable String conversationId) {
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{conversationId}")
    public ResponseEntity<Void> renameConversation(
            @PathVariable String conversationId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<List<MessageVO>> listMessages(@PathVariable String conversationId) {
        return ResponseEntity.ok(Collections.emptyList());
    }

    @PostMapping("/messages/{messageId}/feedback")
    public ResponseEntity<Void> submitFeedback(
            @PathVariable String messageId,
            @RequestBody Map<String, Integer> body) {
        return ResponseEntity.ok().build();
    }
}
