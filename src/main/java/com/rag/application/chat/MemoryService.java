package com.rag.application.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.domain.model.ConversationSummary;
import com.rag.domain.repository.ConversationSummaryRepository;
import com.rag.infrastructure.llm.ChatModelService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private static final String MESSAGE_KEY_PREFIX = "conversation:messages:";
    private static final String SUMMARY_KEY_PREFIX = "conversation:summary:";

    private final StringRedisTemplate redisTemplate;
    private final ConversationSummaryRepository summaryRepository;
    private final ChatModelService chatModel;
    private final ObjectMapper objectMapper;
    private final Executor memorySummaryExecutor;

    @Value("${memory.window-size:10}")
    private int windowSize;

    @Value("${memory.summary-threshold:10}")
    private int summaryThreshold;

    @Value("${memory.ttl-days:7}")
    private int ttlDays;

    public MemoryService(StringRedisTemplate redisTemplate,
                         ConversationSummaryRepository summaryRepository,
                         ChatModelService chatModel,
                         @Qualifier("memorySummaryThreadPoolExecutor") Executor memorySummaryExecutor) {
        this.redisTemplate = redisTemplate;
        this.summaryRepository = summaryRepository;
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
        this.memorySummaryExecutor = memorySummaryExecutor;
    }

    public record Message(String role, String content) {}

    public record ConversationContext(String summary, List<Message> recentMessages) {}

    public ConversationContext getContext(String userId, String conversationId) {
        String messageKey = MESSAGE_KEY_PREFIX + conversationId;
        String summaryKey = SUMMARY_KEY_PREFIX + conversationId;

        try {
            // 并行加载摘要和历史消息
            CompletableFuture<String> summaryFuture = CompletableFuture.supplyAsync(
                    () -> loadSummaryWithFallback(userId, conversationId, summaryKey),
                    memorySummaryExecutor
            );
            CompletableFuture<List<Message>> messagesFuture = CompletableFuture.supplyAsync(
                    () -> loadMessagesWithFallback(messageKey),
                    memorySummaryExecutor
            );

            // 等待所有任务完成
            CompletableFuture.allOf(summaryFuture, messagesFuture).join();

            String existingSummary = summaryFuture.get();
            List<Message> recentMessages = messagesFuture.get();

            log.info("Context for conversation {}: summary={}, recentMessages={}",
                    conversationId, existingSummary != null ? "exists" : "none", recentMessages.size());

            return new ConversationContext(existingSummary, recentMessages);
        } catch (Exception e) {
            log.error("Failed to load context for conversation: {}", conversationId, e);
            return new ConversationContext(null, new ArrayList<>());
        }
    }

    /**
     * 加载摘要，失败时返回 null
     */
    private String loadSummaryWithFallback(String userId, String conversationId, String summaryKey) {
        try {
            // 先从 Redis 获取
            String redisSummary = redisTemplate.opsForValue().get(summaryKey);
            if (redisSummary != null) {
                log.debug("Found summary in Redis for conversation: {}", conversationId);
                return redisSummary;
            }

            // Redis 没有，从 MySQL 获取
            Optional<ConversationSummary> dbSummary = summaryRepository.findByUserIdAndConversationId(userId, conversationId);
            if (dbSummary.isPresent()) {
                String content = dbSummary.get().getContent();
                redisTemplate.opsForValue().set(summaryKey, content, Duration.ofDays(ttlDays));
                log.debug("Loaded summary from MySQL for conversation: {}", conversationId);
                return content;
            }
            return null;
        } catch (Exception e) {
            log.warn("加载摘要失败，将跳过摘要 - conversationId: {}, userId: {}", conversationId, userId, e);
            return null;
        }
    }

    /**
     * 加载历史消息，失败时返回空列表
     */
    private List<Message> loadMessagesWithFallback(String messageKey) {
        try {
            List<String> messagesJson = redisTemplate.opsForList().range(messageKey, 0, -1);
            List<Message> recentMessages = new ArrayList<>();
            if (messagesJson != null) {
                for (String json : messagesJson) {
                    try {
                        recentMessages.add(objectMapper.readValue(json, Message.class));
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to parse message JSON: {}", json);
                    }
                }
            }
            return recentMessages;
        } catch (Exception e) {
            log.error("Failed to load messages: {}", e);
            return new ArrayList<>();
        }
    }

    /**
     * 获取对话历史消息列表（用于 langchain4j 多轮对话）
     */
    public List<ChatMessage> getChatMessages(String userId, String conversationId) {
        List<ChatMessage> messages = new ArrayList<>();
        ConversationContext ctx = getContext(userId, conversationId);

        // 添加摘要作为系统消息（如果有）
        if (ctx.summary() != null && !ctx.summary().isEmpty()) {
            messages.add(SystemMessage.from("[早期对话摘要] " + ctx.summary()));
        }

        // 添加最近消息
        for (Message msg : ctx.recentMessages()) {
            if (msg.content().equals("[早期对话已摘要]")) {
                // 跳过摘要标记
                continue;
            }
            if ("user".equals(msg.role())) {
                messages.add(UserMessage.from(msg.content()));
            } else if ("assistant".equals(msg.role())) {
                messages.add(AiMessage.from(msg.content()));
            } else {
                // 其他角色（如 system）作为 UserMessage 处理
                messages.add(UserMessage.from(msg.content()));
            }
        }

        return messages;
    }

    public void addMessage(String userId, String conversationId, String role, String content) {
        String messageKey = MESSAGE_KEY_PREFIX + conversationId;
        String summaryKey = SUMMARY_KEY_PREFIX + conversationId;

        try {
            Message message = new Message(role, content);
            String json = objectMapper.writeValueAsString(message);

            // 添加到 Redis List
            redisTemplate.opsForList().rightPush(messageKey, json);

            // 设置 TTL
            redisTemplate.expire(messageKey, Duration.ofDays(ttlDays));

            // 检查是否需要生成摘要
            Long messageCount = redisTemplate.opsForList().size(messageKey);
            if (messageCount != null && messageCount >= summaryThreshold) {
                generateSummary(userId, conversationId);
            }

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize message", e);
        }
    }

    private void generateSummary(String userId, String conversationId) {
        // 异步执行摘要生成，不阻塞主流程
        CompletableFuture.runAsync(() -> doGenerateSummary(userId, conversationId), memorySummaryExecutor)
                .exceptionally(ex -> {
                    log.error("异步生成摘要失败 - conversationId: {}, userId: {}", conversationId, userId, ex);
                    return null;
                });
    }

    private void doGenerateSummary(String userId, String conversationId) {
        String messageKey = MESSAGE_KEY_PREFIX + conversationId;
        String summaryKey = SUMMARY_KEY_PREFIX + conversationId;

        log.info("Generating summary for conversation: {}", conversationId);

        // 获取所有消息
        List<String> messagesJson = redisTemplate.opsForList().range(messageKey, 0, -1);
        if (messagesJson == null || messagesJson.isEmpty()) {
            return;
        }

        // 构建摘要 Prompt
        StringBuilder conversationText = new StringBuilder();
        for (String json : messagesJson) {
            try {
                Message msg = objectMapper.readValue(json, Message.class);
                conversationText.append(msg.role()).append(": ").append(msg.content()).append("\n");
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse message for summary: {}", json);
            }
        }

        String summaryPrompt = "请简要总结以下对话的主要内容，保留关键信息（用户问题和助手指南）：\n\n" + conversationText;

        try {
            String summary = chatModel.generate(summaryPrompt);

            // 保存到 MySQL
            ConversationSummary entity = summaryRepository
                    .findByUserIdAndConversationId(userId, conversationId)
                    .orElse(new ConversationSummary(conversationId, userId, null, summary));

            entity.setContent(summary);
            summaryRepository.save(entity);

            // 保存到 Redis
            redisTemplate.opsForValue().set(summaryKey, summary, Duration.ofDays(ttlDays));

            // 清空消息列表，只保留一个摘要标记
            redisTemplate.delete(messageKey);
            String summaryMarker = objectMapper.writeValueAsString(new Message("system", "[早期对话已摘要]"));
            redisTemplate.opsForList().rightPush(messageKey, summaryMarker);

            log.info("Summary generated and saved for conversation: {}", conversationId);

        } catch (Exception e) {
            log.error("Failed to generate summary for conversation: {}", conversationId, e);
        }
    }

    public String buildContextPrompt(String userId, String conversationId) {
        ConversationContext ctx = getContext(userId, conversationId);

        StringBuilder prompt = new StringBuilder();

        // 1. 添加摘要（如果有）
        if (ctx.summary() != null && !ctx.summary().isEmpty()) {
            prompt.append("【对话摘要】\n").append(ctx.summary()).append("\n\n");
        }

        // 2. 添加最近消息
        if (!ctx.recentMessages().isEmpty()) {
            prompt.append("【最近对话】\n");
            for (Message msg : ctx.recentMessages()) {
                if (!msg.content().equals("[早期对话已摘要]")) {
                    prompt.append(msg.role()).append(": ").append(msg.content()).append("\n");
                }
            }
            prompt.append("\n");
        }

        return prompt.toString();
    }

    public void clearConversation(String conversationId) {
        String messageKey = MESSAGE_KEY_PREFIX + conversationId;
        String summaryKey = SUMMARY_KEY_PREFIX + conversationId;

        redisTemplate.delete(messageKey);
        redisTemplate.delete(summaryKey);
        summaryRepository.findByConversationId(conversationId)
                .ifPresent(summaryRepository::delete);

        log.info("Cleared conversation: {}", conversationId);
    }
}
