package com.rag.api.rest.conversation;

import com.rag.application.chat.MemoryService;
import com.rag.domain.model.Conversation;
import com.rag.domain.model.Message;
import com.rag.domain.model.MessageFeedback;
import com.rag.domain.model.User;
import com.rag.domain.repository.ConversationRepository;
import com.rag.domain.repository.MessageRepository;
import com.rag.domain.repository.MessageFeedbackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MessageFeedbackRepository feedbackRepository;
    private final MemoryService memoryService;

    public ConversationController(ConversationRepository conversationRepository,
                                   MessageRepository messageRepository,
                                   MessageFeedbackRepository feedbackRepository,
                                   MemoryService memoryService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.feedbackRepository = feedbackRepository;
        this.memoryService = memoryService;
    }

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
        String userId = getCurrentUserId();
        List<Conversation> conversations;
        if (userId != null) {
            conversations = conversationRepository.findByUserIdAndDeletedFalse(userId);
        } else {
            conversations = conversationRepository.findByDeletedFalseOrderByLastTimeDesc();
        }
        List<ConversationVO> result = conversations.stream()
                .map(c -> new ConversationVO(
                        c.getConversationId(),
                        c.getTitle(),
                        c.getLastTime() != null ? c.getLastTime().toString() : null))
                .sorted((a, b) -> {
                    if (a.lastTime() == null) return 1;
                    if (b.lastTime() == null) return -1;
                    return b.lastTime().compareTo(a.lastTime());
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Void> deleteConversation(@PathVariable String conversationId) {
        conversationRepository.findByConversationId(conversationId).ifPresent(c -> {
            c.setDeleted(true);
            conversationRepository.save(c);
        });
        memoryService.clearConversation(conversationId);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{conversationId}")
    public ResponseEntity<Void> renameConversation(
            @PathVariable String conversationId,
            @RequestBody Map<String, String> body) {
        String newTitle = body.get("title");
        if (newTitle == null || newTitle.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        conversationRepository.findByConversationId(conversationId).ifPresent(c -> {
            c.setTitle(newTitle);
            conversationRepository.save(c);
        });
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<List<MessageVO>> listMessages(@PathVariable String conversationId) {
        String userId = getCurrentUserId();
        List<MessageVO> result = new ArrayList<>();

        // 先从 Redis 获取最近的消息（快速路径）
        if (userId != null) {
            MemoryService.ConversationContext ctx = memoryService.getContext(userId, conversationId);
            for (MemoryService.Message memMsg : ctx.recentMessages()) {
                if (memMsg.content().equals("[早期对话已摘要]")) continue;
                result.add(new MessageVO(
                        UUID.randomUUID().toString(),
                        conversationId,
                        memMsg.role(),
                        memMsg.content(),
                        null, null, null,
                        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                ));
            }
        }

        // 如果 Redis 为空，从 MySQL 加载
        if (result.isEmpty()) {
            List<Message> messages = messageRepository.findByConversationIdAndDeletedFalseOrderByCreateTimeAsc(conversationId);
            for (Message msg : messages) {
                result.add(new MessageVO(
                        msg.getId(),
                        conversationId,
                        msg.getRole().toLowerCase(),
                        msg.getContent(),
                        null, null, null,
                        msg.getCreateTime() != null ? msg.getCreateTime().toString() : null
                ));
            }
        }

        return ResponseEntity.ok(result);
    }

    @PostMapping("/messages/{messageId}/feedback")
    public ResponseEntity<Void> submitFeedback(
            @PathVariable String messageId,
            @RequestBody Map<String, Integer> body) {
        String userId = getCurrentUserId();
        Integer vote = body.get("vote");
        if (vote == null || userId == null) {
            return ResponseEntity.badRequest().build();
        }
        MessageFeedback feedback = new MessageFeedback();
        feedback.setConversationId(messageId);
        feedback.setUserId(userId);
        feedback.setVote(vote);
        feedback.setCreateTime(LocalDateTime.now());
        feedback.setUpdateTime(LocalDateTime.now());
        try {
            feedback.setMessageId(Long.parseLong(messageId));
        } catch (NumberFormatException e) {
            feedback.setMessageId(0L);
        }
        feedbackRepository.save(feedback);
        return ResponseEntity.ok().build();
    }

    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user.getId().toString();
        }
        return null;
    }
}
