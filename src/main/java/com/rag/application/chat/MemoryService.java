package com.rag.application.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.domain.model.ConversationSummary;
import com.rag.domain.repository.ConversationSummaryRepository;
import com.rag.domain.repository.MessageRepository;
import com.rag.infrastructure.llm.ChatModelService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private static final String MESSAGE_KEY_PREFIX = "conversation:messages:";
    private static final String SUMMARY_KEY_PREFIX = "conversation:summary:";
    /** 窗口内表示"更早内容已并入摘要"的占位消息，消费端按内容跳过 */
    private static final String SUMMARY_MARKER = "[早期对话已摘要]";

    private final StringRedisTemplate redisTemplate;
    private final ConversationSummaryRepository summaryRepository;
    private final MessageRepository messageRepository;
    private final ChatModelService chatModel;
    private final ObjectMapper objectMapper;
    private final Executor memorySummaryExecutor;

    /** 回源重建时恢复的窗口大小（终于被使用的 window-size） */
    @Value("${memory.window-size:10}")
    private int windowSize;

    /** 条数触发阈值（辅助触发，主要触发见 triggerTokens） */
    @Value("${memory.summary-threshold:8}")
    private int summaryThreshold;

    /** 窗口估算 token 达到该值即触发摘要（单条长消息不受条数阈值保护，见测试报告 T4） */
    @Value("${memory.trigger-tokens:2500}")
    private int triggerTokens;

    /** 记忆上下文（摘要+最近消息）的总 token 预算 */
    @Value("${memory.context-max-tokens:3000}")
    private int contextMaxTokens;

    @Value("${memory.ttl-days:7}")
    private int ttlDays;

    /** 同一会话的摘要任务去重：防止摘要执行期间新消息再次触发重复 LLM 调用 */
    private final Set<String> inFlightSummaries = ConcurrentHashMap.newKeySet();

    /**
     * 会话级写锁：保证同一会话的"MySQL 落库（分配自增 id）→ Redis 推入"成对原子。
     * 没有它，两个并发写线程会造成 id 分配顺序与推入顺序倒挂
     * （水位线越过尚未推入的消息，破坏水位线语义——soak 迭代 87/50 发现）。
     */
    private final ConcurrentHashMap<String, Object> conversationWriteLocks = new ConcurrentHashMap<>();

    private Object writeLockFor(String conversationId) {
        return conversationWriteLocks.computeIfAbsent(conversationId, k -> new Object());
    }

    private final Counter summaryOkCounter;
    private final Counter summaryFailCounter;
    private final Counter rebuildCounter;
    private final DistributionSummary contextTokensSummary;

    public MemoryService(StringRedisTemplate redisTemplate,
                         ConversationSummaryRepository summaryRepository,
                         MessageRepository messageRepository,
                         ChatModelService chatModel,
                         @Qualifier("memorySummaryThreadPoolExecutor") Executor memorySummaryExecutor,
                         MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.summaryRepository = summaryRepository;
        this.messageRepository = messageRepository;
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
        this.memorySummaryExecutor = memorySummaryExecutor;
        this.summaryOkCounter = Counter.builder("memory.summary.total")
                .tag("result", "ok").description("成功持久化的会话摘要数").register(meterRegistry);
        this.summaryFailCounter = Counter.builder("memory.summary.total")
                .tag("result", "fail").description("失败的会话摘要数").register(meterRegistry);
        this.rebuildCounter = Counter.builder("memory.rebuild.total")
                .tag("source", "mysql").description("Redis 窗口失效后从 MySQL 回源重建次数").register(meterRegistry);
        this.contextTokensSummary = DistributionSummary.builder("memory.context.tokens")
                .description("记忆上下文的估算 token 数").register(meterRegistry);
    }

    public record Message(Long id, String role, String content) {}

    public record ConversationContext(String summary, List<Message> recentMessages) {}

    private record BoundedMemory(String summary, List<Message> messages, int estimatedTokens) {}

    // ---------- 读取路径 ----------

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
                    () -> loadMessagesWithFallback(userId, conversationId, messageKey),
                    memorySummaryExecutor
            );

            // 等待所有任务完成
            CompletableFuture.allOf(summaryFuture, messagesFuture).join();

            String existingSummary = summaryFuture.get();
            List<Message> recentMessages = messagesFuture.get();

            log.debug("Context for conversation {}: summary={}, recentMessages={}",
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
                return redisSummary;
            }

            // Redis 没有，从 MySQL 获取并回填
            Optional<ConversationSummary> dbSummary = summaryRepository.findByUserIdAndConversationId(userId, conversationId);
            if (dbSummary.isPresent()) {
                String content = dbSummary.get().getContent();
                redisTemplate.opsForValue().set(summaryKey, content, Duration.ofDays(ttlDays));
                return content;
            }
            return null;
        } catch (Exception e) {
            log.warn("加载摘要失败，将跳过摘要 - conversationId: {}, userId: {}", conversationId, userId, e);
            return null;
        }
    }

    /**
     * 加载最近消息窗口；检测窗口缺口时从 MySQL 补齐（read-through）。
     *
     * 缺口有两种来源：窗口整体失效（TTL 过期/实例重启，窗口为空），
     * 以及部分失效后未经历重建就有新写入（新消息挡住了空窗口判断，旧消息缺位）。
     * 补齐下界是摘要水位线（summary.lastMessageId）——已并入摘要的消息不复活。
     */
    private List<Message> loadMessagesWithFallback(String userId, String conversationId, String messageKey) {
        try {
            // 顺序关键：先读窗口、再读水位线。若相反，摘要线程"保存水位线 → LREM"的中间态
            // 会让补齐用旧水位线把已摘要消息重新加回窗口（soak 迭代 87 发现的复活路径）。
            List<Message> window = parseMessages(redisTemplate.opsForList().range(messageKey, 0, -1));
            if (inFlightSummaries.contains(conversationId)) {
                // 摘要在途：窗口状态尚未稳定（LREM 进行中），跳过补齐判断，缺失由下次读取兜底
                return window.stream()
                        .filter(m -> !SUMMARY_MARKER.equals(m.content()))
                        .toList();
            }
            return reconcileWindow(conversationId, messageKey);
        } catch (Exception e) {
            log.error("Failed to load messages: {}", e);
            return new ArrayList<>();
        }
    }

    /**
     * 窗口缺口对账：窗口内未摘要消息数少于"MySQL 中水位线之后的消息数"即为有缺口
     * （整体失效后未重建，或部分失效后未经历重建就有新写入），
     * 补齐水位线之后缺失的消息。缺失块必然位于窗口旧端（追加写入按 id 递增），
     * 按 id 降序 leftPush 逐条前插，最终列表保持时间正序。
     * 持有会话写锁执行，避免与并发写入/其他对账交错产生重复条目。
     *
     * 关键不变量：任何一次摘要的快照都必须基于对账后的连续窗口——
     * 否则水位线（快照最大 id）会跳过洞里的未摘要消息，使其成为永久孤儿
     * （soak 迭代 12 发现的丢失路径）。
     *
     * 另一关键点：拿锁后必须重新读取窗口，绝不信任调用方传入的快照——
     * 窗口可能在"调用方读取"与"本方法拿锁"之间被 flush/崩溃清空，
     * 基于幽灵内容的对账会把"全缺"误判为"缺一条"（soak 迭代 12 复盘确认）。
     */
    private List<Message> reconcileWindow(String conversationId, String messageKey) {
        synchronized (writeLockFor(conversationId)) {
            List<Message> fresh = parseMessages(redisTemplate.opsForList().range(messageKey, 0, -1));
            long watermark = loadWatermark(conversationId);
            long expected = messageRepository.countByConversationIdAndIdGreaterThan(conversationId, watermark);
            List<Message> present = fresh.stream()
                    .filter(m -> !SUMMARY_MARKER.equals(m.content()))
                    .toList();
            if (present.size() >= expected) {
                return present;
            }

            int fetch = (int) Math.max(windowSize, expected);
            List<com.rag.domain.model.Message> rows = messageRepository
                    .findByConversationIdAndIdGreaterThanOrderByIdDesc(
                            conversationId, watermark, PageRequest.of(0, fetch));
            Set<Long> presentIds = present.stream()
                    .map(Message::id)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            int restored = 0;
            try {
                for (com.rag.domain.model.Message row : rows) {
                    if (!presentIds.contains(row.getId())) {
                        redisTemplate.opsForList().leftPush(messageKey,
                                objectMapper.writeValueAsString(new Message(row.getId(), row.getRole(), row.getContent())));
                        restored++;
                    }
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize restored messages: {}", e.getMessage());
            }
            if (restored > 0) {
                rebuildCounter.increment();
                redisTemplate.expire(messageKey, Duration.ofDays(ttlDays));
                log.info("Window gap filled from MySQL - conversationId: {}, restored: {}",
                        conversationId, restored);
                if (conversationId.startsWith("memtest-soak-")) {
                    log.info("[DBG] conv={} gap-fill restored={} rows={}", conversationId, restored,
                            rows.stream().map(com.rag.domain.model.Message::getId).toList());
                }
            }
            return parseMessages(redisTemplate.opsForList().range(messageKey, 0, -1)).stream()
                    .filter(m -> !SUMMARY_MARKER.equals(m.content()))
                    .toList();
        }
    }

    /** 读取摘要水位线（已并入摘要的最大消息 id），无摘要时为 0 */
    private long loadWatermark(String conversationId) {
        return summaryRepository.findByConversationId(conversationId)
                .map(s -> parseWatermark(s.getLastMessageId()))
                .orElse(0L);
    }

    private List<Message> parseMessages(List<String> messagesJson) {
        List<Message> messages = new ArrayList<>();
        if (messagesJson == null) {
            return messages;
        }
        for (String json : messagesJson) {
            try {
                messages.add(objectMapper.readValue(json, Message.class));
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse message JSON: {}", json);
            }
        }
        return messages;
    }

    private long parseWatermark(String lastMessageId) {
        if (lastMessageId == null || lastMessageId.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(lastMessageId);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------- 记忆上下文构建（token 预算内） ----------

    /**
     * 获取对话历史消息列表（用于 langchain4j 多轮对话）
     */
    public List<ChatMessage> getChatMessages(String userId, String conversationId) {
        BoundedMemory bounded = loadBoundedMemory(userId, conversationId);
        List<ChatMessage> messages = new ArrayList<>();

        // 添加摘要作为系统消息（如果有）
        if (bounded.summary() != null && !bounded.summary().isEmpty()) {
            messages.add(SystemMessage.from("[早期对话摘要] " + bounded.summary()));
        }

        for (Message msg : bounded.messages()) {
            if (SUMMARY_MARKER.equals(msg.content())) {
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

        try {
            synchronized (writeLockFor(conversationId)) {
                // 先落 MySQL 拿自增 id 作为消息水位线，再写 Redis 窗口；
                // 全程持有会话写锁，保证 id 序 == 推入序（水位线语义的前提）
                Long id = null;
                try {
                    com.rag.domain.model.Message msg = new com.rag.domain.model.Message(
                            conversationId, userId, role, content);
                    id = messageRepository.save(msg).getId();
                } catch (Exception e) {
                    log.warn("Failed to persist message to MySQL: {}", e.getMessage());
                }

                Message message = new Message(id, role, content);
                String json = objectMapper.writeValueAsString(message);
                redisTemplate.opsForList().rightPush(messageKey, json);
                redisTemplate.expire(messageKey, Duration.ofDays(ttlDays));

                if (shouldSummarize(messageKey)) {
                    generateSummary(userId, conversationId);
                }
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize message", e);
        }
    }

    /**
     * 摘要触发条件：条数达阈值（辅助）或窗口估算 token 达预算（主）。
     * 条数对小消息不敏感，单条长消息也可能击穿上下文预算，两者取或。
     */
    private boolean shouldSummarize(String messageKey) {
        Long count = redisTemplate.opsForList().size(messageKey);
        if (count != null && count >= summaryThreshold) {
            return true;
        }
        List<String> messagesJson = redisTemplate.opsForList().range(messageKey, 0, -1);
        long tokens = 0;
        for (Message m : parseMessages(messagesJson)) {
            if (!SUMMARY_MARKER.equals(m.content())) {
                tokens += estimateTokens(m.content());
            }
        }
        return tokens >= triggerTokens;
    }

    private void generateSummary(String userId, String conversationId) {
        // 同会话去重：摘要已在执行/排队时不再重复提交，避免并发 LLM 调用与重复摘要
        if (!inFlightSummaries.add(conversationId)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                doGenerateSummary(userId, conversationId);
            } finally {
                inFlightSummaries.remove(conversationId);
            }
        }, memorySummaryExecutor).exceptionally(ex -> {
            log.error("异步生成摘要失败 - conversationId: {}, userId: {}", conversationId, userId, ex);
            summaryFailCounter.increment();
            return null;
        });
    }

    private void doGenerateSummary(String userId, String conversationId) {
        String messageKey = MESSAGE_KEY_PREFIX + conversationId;
        String summaryKey = SUMMARY_KEY_PREFIX + conversationId;

        try {
            log.info("Generating summary for conversation: {}", conversationId);

            // 对账先行：摘要快照必须基于补齐后的连续窗口。
            // 若窗口有洞（部分失效后未重建即有新写入），水位线（快照最大 id）会跳过洞里的
            // 未摘要消息使其成为永久孤儿（soak 迭代 12 发现的丢失路径）。
            reconcileWindow(conversationId, messageKey);
            List<String> messagesJson = redisTemplate.opsForList().range(messageKey, 0, -1);
            if (messagesJson == null || messagesJson.isEmpty()) {
                return;
            }

            // 快照解析：剔除占位标记，只摘要真实消息
            List<Message> snapshot = new ArrayList<>();
            for (Message m : parseMessages(messagesJson)) {
                if (!SUMMARY_MARKER.equals(m.content())) {
                    snapshot.add(m);
                }
            }
            if (snapshot.isEmpty()) {
                return;
            }

            // 滚动合并：旧摘要 + 新消息 → 新摘要（覆盖式摘要是长对话信息断代的根因）
            String oldSummary = loadSummaryWithFallback(userId, conversationId, summaryKey);
            StringBuilder conversationText = new StringBuilder();
            for (Message msg : snapshot) {
                conversationText.append(msg.role()).append(": ").append(msg.content()).append("\n");
            }
            String summary = chatModel.generate(buildSummaryPrompt(oldSummary, conversationText.toString()));

            // 持久化：MySQL 摘要（lastMessageId 记录水位线）+ Redis 缓存
            Long watermark = snapshot.stream()
                    .map(Message::id)
                    .filter(Objects::nonNull)
                    .max(Long::compare)
                    .orElse(null);

            ConversationSummary entity = summaryRepository
                    .findByUserIdAndConversationId(userId, conversationId)
                    .orElseGet(() -> new ConversationSummary(conversationId, userId, "", summary));
            entity.setContent(summary);
            if (watermark != null) {
                entity.setLastMessageId(String.valueOf(watermark));
            }
            summaryRepository.save(entity);
            if (conversationId.startsWith("memtest-soak-")) {
                log.info("[DBG] conv={} summary saved watermark={}", conversationId, watermark);
            }

            redisTemplate.opsForValue().set(summaryKey, summary, Duration.ofDays(ttlDays));

            // 按值精确删除快照条目（含旧占位标记）：只移除本次摘要覆盖的消息。
            // 不能用 LTRIM 按索引截断——并发的窗口缺口补齐（leftPush）会改变列表内容，
            // 使快照索引错位，切掉既不在窗口也不在任何摘要里的消息（soak 测试发现的丢失路径）。
            for (String snapshotJson : messagesJson) {
                redisTemplate.opsForList().remove(messageKey, 1, snapshotJson);
            }
            if (conversationId.startsWith("memtest-soak-")) {
                log.info("[DBG] conv={} lrem done n={} watermark={}", conversationId, messagesJson.size(), watermark);
            }
            redisTemplate.opsForList().rightPush(messageKey,
                    objectMapper.writeValueAsString(new Message(watermark, "system", SUMMARY_MARKER)));
            redisTemplate.expire(messageKey, Duration.ofDays(ttlDays));

            summaryOkCounter.increment();
            log.info("Summary generated and saved for conversation: {}", conversationId);

        } catch (Exception e) {
            summaryFailCounter.increment();
            log.error("Failed to generate summary for conversation: {}", conversationId, e);
        }
    }

    private String buildSummaryPrompt(String oldSummary, String conversationText) {
        if (oldSummary == null || oldSummary.isBlank()) {
            return "请简要总结以下对话的主要内容，保留关键信息（用户问题和助手指南）：\n\n" + conversationText;
        }
        return """
                请将【旧摘要】与【新对话】合并为一份简要总结：
                - 保留旧摘要中仍然有效的关键信息
                - 融入新对话中的新信息
                - 两者冲突时以新对话为准
                - 直接输出合并后的摘要，不要解释

                【旧摘要】
                %s

                【新对话】
                %s

                合并后的摘要:
                """.formatted(oldSummary, conversationText);
    }

    public String buildContextPrompt(String userId, String conversationId) {
        BoundedMemory bounded = loadBoundedMemory(userId, conversationId);
        contextTokensSummary.record(bounded.estimatedTokens());

        StringBuilder prompt = new StringBuilder();

        // 1. 添加摘要（如果有）
        if (bounded.summary() != null && !bounded.summary().isEmpty()) {
            prompt.append("【对话摘要】\n").append(bounded.summary()).append("\n\n");
        }

        // 2. 添加最近消息
        if (!bounded.messages().isEmpty()) {
            prompt.append("【最近对话】\n");
            for (Message msg : bounded.messages()) {
                if (!SUMMARY_MARKER.equals(msg.content())) {
                    prompt.append(msg.role()).append(": ").append(msg.content()).append("\n");
                }
            }
            prompt.append("\n");
        }

        return prompt.toString();
    }

    /**
     * 在 token 预算内构建记忆：摘要优先占位，最近消息从最新到最旧纳入，放不下的丢弃。
     * 保证上下文长度有硬上界（测试报告 T4：此前无任何截断，单条 3 万字符消息原样进入上下文）。
     */
    private BoundedMemory loadBoundedMemory(String userId, String conversationId) {
        ConversationContext ctx = getContext(userId, conversationId);

        int budget = Math.max(0, contextMaxTokens);
        String summary = ctx.summary();
        int summaryTokens = 0;
        if (summary != null && !summary.isEmpty()) {
            summaryTokens = estimateTokens(summary);
            if (summaryTokens > budget) {
                summary = truncateToTokens(summary, budget);
                summaryTokens = budget;
            }
        }

        List<Message> selected = new ArrayList<>();
        int used = summaryTokens;
        List<Message> recent = ctx.recentMessages();
        for (int i = recent.size() - 1; i >= 0 && used < budget; i--) {
            Message m = recent.get(i);
            if (SUMMARY_MARKER.equals(m.content())) {
                continue;
            }
            int t = estimateTokens(m.content());
            if (used + t > budget) {
                break; // 预算用尽：保留最新的，丢弃更旧的
            }
            selected.add(m);
            used += t;
        }
        Collections.reverse(selected); // 恢复时间正序

        return new BoundedMemory(summary, selected, used);
    }

    /**
     * 粗略 token 估算：CJK 字符约 1 字 1 token，其他字符约 4 字符 1 token。
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }

    private boolean isCjk(char c) {
        return (c >= 0x2E80 && c <= 0x9FFF)   // CJK 部首/符号/统一表意文字
                || (c >= 0xF900 && c <= 0xFAFF) // CJK 兼容表意文字
                || (c >= 0xFF00 && c <= 0xFFEF); // 全角符号
    }

    private String truncateToTokens(String text, int maxTokens) {
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length() && used < maxTokens; i++) {
            char c = text.charAt(i);
            sb.append(c);
            used += isCjk(c) ? 1 : 0; // 保守按 CJK 计
        }
        return sb.toString();
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
