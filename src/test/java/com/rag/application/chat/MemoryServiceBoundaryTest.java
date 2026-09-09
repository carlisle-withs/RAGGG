package com.rag.application.chat;

import com.rag.domain.model.ConversationSummary;
import com.rag.domain.repository.ConversationSummaryRepository;
import com.rag.domain.repository.MessageRepository;
import com.rag.infrastructure.llm.ChatModelService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记忆系统边界测试（T1-T7）。
 *
 * 被测对象：MemoryService（application/chat/MemoryService.java）
 * 测试策略：真实 Redis + 真实 MySQL（rag_system 库），LLM 用 Mockito 模拟，
 *           通过在 mock 内阻塞来精确控制异步摘要的时序，使竞态可确定性复现。
 *
 * 历史：T1-T5 首轮执行（2026-09-05）全部失败，暴露 5 项契约违反与 1 项 P0 bug
 * （摘要持久化必然失败），详见 docs/testing/记忆系统测试报告.md。
 * 修复实施后（水位线+LTRIM、滚动合并摘要、MySQL 回源重建、token 预算、并发去重），
 * 本套件转为回归测试，全部通过方为合格。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// 关闭 @DataJpaTest 默认的测试管理事务：必须用 Spring 的 @Transactional(NOT_SUPPORTED)。
// （jakarta 的注解不会生效，主线程写入将停留在未提交事务中，异步线程与 jdbcTemplate
//   之外的连接在 REPEATABLE READ 下看不到数据，重建路径会返回空。）
@Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3307/rag_system?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=123456",
        "spring.jpa.hibernate.ddl-auto=none"
})
@Import(MemoryServiceBoundaryTest.RedisTestConfig.class)
class MemoryServiceBoundaryTest {

    /** 测试数据隔离前缀：Redis key 与 MySQL 行均以此标识，@AfterEach 统一清理 */
    private static final String CONV_PREFIX = "memtest-";
    /** 摘要触发阈值，与生产配置 application.yml 的 memory.summary-threshold=8 一致 */
    private static final int SUMMARY_THRESHOLD = 8;
    /** 上下文预算断言值：12000 字符 ≈ 3000 token（中文 1 字 ≈ 1 token） */
    private static final int CONTEXT_BUDGET_CHARS = 12_000;
    private static final String MARKER = "[早期对话已摘要]";

    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private ConversationSummaryRepository summaryRepository;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ChatModelService chatModel;
    private MemoryService memoryService;
    private SimpleMeterRegistry meterRegistry;
    private final List<String> capturedPrompts = new ArrayList<>();
    private final AtomicInteger generateCalls = new AtomicInteger();

    private static Executor summaryExecutor;

    @TestConfiguration
    static class RedisTestConfig {
        /** 本机 docker-compose 拓扑中 raggg-redis 映射在 26379 端口（application.yml 中的 6380 为过期配置） */
        @Bean
        public RedisConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory("127.0.0.1", 26379);
        }

        @Bean
        public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
            return new StringRedisTemplate(factory);
        }
    }

    @BeforeEach
    void setUp() {
        capturedPrompts.clear();
        generateCalls.set(0);
        meterRegistry = new SimpleMeterRegistry();
        chatModel = Mockito.mock(ChatModelService.class);
        // 默认桩：记录 prompt 并立即返回固定摘要。使用 doAnswer 形式：
        // when(mock.foo()) 形式在后续"重新 stub"时会重放本 answer（anyString() 默认 ""）。
        Mockito.doAnswer(inv -> {
            capturedPrompts.add(inv.getArgument(0, String.class));
            generateCalls.incrementAndGet();
            return "（模拟摘要）";
        }).when(chatModel).generate(Mockito.anyString());
        summaryExecutor = Executors.newSingleThreadExecutor();
        memoryService = new MemoryService(redis, summaryRepository, messageRepository, chatModel,
                summaryExecutor, meterRegistry);
        ReflectionTestUtils.setField(memoryService, "windowSize", 10);
        ReflectionTestUtils.setField(memoryService, "summaryThreshold", SUMMARY_THRESHOLD);
        ReflectionTestUtils.setField(memoryService, "triggerTokens", 10_000); // 默认关闭 token 触发，避免干扰条数触发的用例
        ReflectionTestUtils.setField(memoryService, "contextMaxTokens", 3_000);
        ReflectionTestUtils.setField(memoryService, "ttlDays", 7);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM t_message WHERE conversation_id LIKE ?", CONV_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM t_conversation_summary WHERE conversation_id LIKE ?", CONV_PREFIX + "%");
        List<String> keys = new ArrayList<>();
        keys.addAll(redis.keys("conversation:messages:" + CONV_PREFIX + "*"));
        keys.addAll(redis.keys("conversation:summary:" + CONV_PREFIX + "*"));
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @AfterAll
    static void tearDown() {
        if (summaryExecutor != null) {
            summaryExecutor = null; // JVM 退出时由测试进程回收
        }
    }

    // ---------- 工具方法 ----------

    private String newConv() {
        return CONV_PREFIX + UUID.randomUUID();
    }

    /** 轮询等待条件成立，超时返回 false（避免固定 sleep 的脆弱性） */
    private boolean await(Duration timeout, Supplier<Boolean> cond) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(cond.get())) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private List<String> redisMessages(String conv) {
        List<String> raw = redis.opsForList().range("conversation:messages:" + conv, 0, -1);
        return raw == null ? List.of() : raw;
    }

    private boolean markerPresent(String conv) {
        return redisMessages(conv).stream().anyMatch(m -> m.contains(MARKER));
    }

    private String redisSummary(String conv) {
        return redis.opsForValue().get("conversation:summary:" + conv);
    }

    private int dbMessageCount(String conv) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_message WHERE conversation_id = ?", Integer.class, conv);
        return n == null ? 0 : n;
    }

    private Long dbMaxMessageId(String conv) {
        return jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM t_message WHERE conversation_id = ?", Long.class, conv);
    }

    private String dbSummaryWatermark(String conv) {
        return jdbcTemplate.queryForObject(
                "SELECT last_message_id FROM t_conversation_summary WHERE conversation_id = ?",
                String.class, conv);
    }

    // ---------- 契约/回归测试 ----------

    @Test
    @DisplayName("T1 竞态回归：摘要执行期间新写入的消息必须留在热窗口（LTRIM 截断 + 同会话摘要去重）")
    void t1_messageAddedDuringSummaryMustNotBeLost() throws Exception {
        String conv = newConv();
        String msg9 = "第九条消息：这是一条在摘要执行期间写入的消息，关键词是独角兽";

        // mock：第一次 generate 进入时挂起，精确模拟"摘要正在调用 LLM"的时间窗
        CountDownLatch enteredGenerate = new CountDownLatch(1);
        CountDownLatch releaseGenerate = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger();
        Mockito.doAnswer(inv -> {
            capturedPrompts.add(inv.getArgument(0, String.class));
            if (callCount.incrementAndGet() == 1) {
                enteredGenerate.countDown();          // 此刻摘要线程已完成 List 快照读取
                releaseGenerate.await(10, TimeUnit.SECONDS);
            }
            generateCalls.incrementAndGet();
            return "（模拟摘要）";
        }).when(chatModel).generate(Mockito.anyString());

        for (int i = 1; i <= 8; i++) {
            memoryService.addMessage("u1", conv, "user", "第" + i + "条消息");
        }
        // 等摘要线程真正进入 generate：保证其 List 快照只含前 8 条
        assertThat(enteredGenerate.await(5, TimeUnit.SECONDS)).isTrue();

        // 摘要执行期间，用户继续对话写入第 9 条
        memoryService.addMessage("u1", conv, "user", msg9);
        releaseGenerate.countDown();

        // 等待摘要完成（窗口被 LTRIM 截断并追加占位标记）
        assertThat(await(Duration.ofSeconds(10), () -> generateCalls.get() >= 1 && markerPresent(conv))).isTrue();

        boolean msg9InHotWindow = redisMessages(conv).stream().anyMatch(m -> m.contains("独角兽"));
        boolean msg9InAnySummary = capturedPrompts.stream().anyMatch(p -> p.contains("独角兽"));

        System.out.println("[T1] 摘要 prompt 数=" + capturedPrompts.size()
                + " | 第9条在热窗口=" + msg9InHotWindow
                + " | 第9条进入过任何摘要=" + msg9InAnySummary);
        System.out.println("[T1] 摘要后热窗口内容=" + redisMessages(conv));

        // ===== 契约断言 =====
        assertThat(dbMessageCount(conv)).as("MySQL 应有 9 条消息").isEqualTo(9);
        assertThat(generateCalls.get()).as("同会话摘要去重：第 9 条触发的第二次摘要应被跳过").isEqualTo(1);
        assertThat(msg9InHotWindow || msg9InAnySummary)
                .as("T1 竞态契约：摘要期间写入的消息不应从模型可见的记忆中消失")
                .isTrue();
        assertThat(msg9InHotWindow)
                .as("修复后语义：第 9 条消息应由 LTRIM 保留在热窗口中")
                .isTrue();
    }

    @Test
    @DisplayName("T2 合并回归：第二次摘要的输入必须包含旧摘要内容（滚动合并而非覆盖）")
    void t2_secondSummaryPromptMustContainPreviousSummary() throws Exception {
        String conv = newConv();
        String earlyFact = "我的知识库代号是阿尔法计划";

        // 摘要桩返回"回声"：摘要文本中带有输入 prompt 的内容，
        // 从而使旧摘要文本可被追踪（否则 mock 摘要与输入无关，合并断言无从谈起）
        Mockito.doAnswer(inv -> {
            String p = inv.getArgument(0, String.class);
            capturedPrompts.add(p);
            generateCalls.incrementAndGet();
            return "[摘要回声]" + p;
        }).when(chatModel).generate(Mockito.anyString());

        // 周期1：写满 8 条触发第一次摘要，第一条携带早期关键事实
        memoryService.addMessage("u1", conv, "user", earlyFact);
        for (int i = 2; i <= 8; i++) {
            memoryService.addMessage("u1", conv, "user", "周期一第" + i + "条，讨论主题" + i);
        }
        assertThat(await(Duration.ofSeconds(10), () -> generateCalls.get() >= 1 && markerPresent(conv))).isTrue();
        assertThat(redisSummary(conv)).as("第一次摘要应已生成").isNotNull();
        assertThat(capturedPrompts.get(0)).as("第一次摘要输入应包含早期事实").contains("阿尔法计划");

        // 周期2：再写 7 条触发第二次摘要
        for (int i = 1; i <= 7; i++) {
            memoryService.addMessage("u1", conv, "user", "周期二第" + i + "条，全新话题" + i);
        }
        assertThat(await(Duration.ofSeconds(10), () -> generateCalls.get() >= 2 && markerPresent(conv))).isTrue();

        String prompt2 = capturedPrompts.get(1);
        System.out.println("[T2] 第二次摘要的输入 prompt 前 200 字=\n"
                + prompt2.substring(0, Math.min(200, prompt2.length())));

        // ===== 契约断言 =====
        assertThat(prompt2).as("第二次摘要应采用合并模板（含旧摘要区）").contains("旧摘要");
        assertThat(prompt2)
                .as("T2 合并契约：第二次摘要的输入应包含旧摘要（经由回声可追踪到阿尔法计划）")
                .contains("阿尔法计划");
    }

    @Test
    @DisplayName("T3 水位线回归：Redis 失效后摘要可回源，且已摘要消息不得复活进窗口")
    void t3_rebuildMustRespectWatermark() throws Exception {
        String conv = newConv();
        for (int i = 1; i <= 8; i++) {
            memoryService.addMessage("u1", conv, "user", "回源测试第" + i + "条");
        }
        assertThat(await(Duration.ofSeconds(10), () -> generateCalls.get() >= 1 && markerPresent(conv))).isTrue();

        Long maxId = dbMaxMessageId(conv);
        assertThat(dbSummaryWatermark(conv))
                .as("摘要水位线应持久化且等于已摘要消息的最大 id")
                .isEqualTo(String.valueOf(maxId));

        // 模拟 Redis 消息窗口失效（TTL 到期 / 实例重启），仅删消息 key，保留摘要 key
        redis.delete("conversation:messages:" + conv);

        MemoryService.ConversationContext ctx = memoryService.getContext("u1", conv);

        System.out.println("[T3] Redis 丢失后：summary 存在=" + (ctx.summary() != null)
                + " | recentMessages=" + ctx.recentMessages().size()
                + " | MySQL 行数=" + dbMessageCount(conv)
                + " | 回源次数=" + meterRegistry.get("memory.rebuild.total").counter().count());

        // ===== 契约断言 =====
        assertThat(ctx.summary()).as("摘要应从冷层回源（read-through）").isNotNull();
        assertThat(ctx.recentMessages())
                .as("T3 水位线契约：8 条消息均已并入摘要（水位线=id8），重建不得把它们复活进窗口造成上下文重复")
                .isEmpty();
        assertThat(meterRegistry.get("memory.rebuild.total").counter().count())
                .as("无待恢复消息（水位线后为空），重建计数不应增加")
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("T4 预算回归：buildContextPrompt 的输出长度必须有上界（≤3000 token）")
    void t4_contextPromptMustHaveBudgetBound() {
        String conv = newConv();
        // 单条超长消息：30000 字符 ≈ 30000 token（中文 1 字≈1 token）
        String huge = "超长消息内容" + "测".repeat(30_000);
        memoryService.addMessage("u1", conv, "user", huge);

        String prompt = memoryService.buildContextPrompt("u1", conv);
        System.out.println("[T4] 单条 30000 字符消息 → buildContextPrompt 输出 " + prompt.length() + " 字符"
                + " | context_tokens 计数=" + meterRegistry.get("memory.context.tokens")
                        .summary().totalAmount());

        // 累积场景：6 条 × 5000 字符
        String conv2 = newConv();
        for (int i = 1; i <= 6; i++) {
            memoryService.addMessage("u1", conv2, "user", "累积消息" + i + "：" + "长".repeat(5_000));
        }
        String prompt2 = memoryService.buildContextPrompt("u1", conv2);
        System.out.println("[T4] 6×5000 字符消息 → buildContextPrompt 输出 " + prompt2.length() + " 字符");

        // ===== 契约断言 =====
        assertThat(prompt.length())
                .as("T4 预算契约：上下文构建应受 token 预算约束")
                .isLessThanOrEqualTo(CONTEXT_BUDGET_CHARS);
        assertThat(prompt2.length())
                .as("T4 预算契约：累积长消息同样受预算约束")
                .isLessThanOrEqualTo(CONTEXT_BUDGET_CHARS);
    }

    @Test
    @DisplayName("T5 冷启动回归：未达摘要阈值的短会话，Redis 失效后消息必须从 MySQL 完整重建")
    void t5_shortConversationMustNotSufferTotalAmnesia() {
        String conv = newConv();
        for (int i = 1; i <= 3; i++) {
            memoryService.addMessage("u1", conv, "user", "短会话第" + i + "条，用户提到部署在测试环境");
        }
        // 未达阈值，不应有摘要
        assertThat(redisSummary(conv)).as("3 条消息不应触发摘要").isNull();

        // 模拟 TTL 过期 / Redis 重启
        redis.delete("conversation:messages:" + conv);

        MemoryService.ConversationContext ctx = memoryService.getContext("u1", conv);

        System.out.println("[T5] Redis 失效后：summary=" + ctx.summary()
                + " | recentMessages=" + ctx.recentMessages().size()
                + " | MySQL 行数=" + dbMessageCount(conv));

        // ===== 契约断言 =====
        assertThat(ctx.summary()).as("短会话本就无摘要").isNull();
        assertThat(ctx.recentMessages())
                .as("T5 冷启动契约：MySQL 中尚有 3 条消息时，模型侧记忆不应完全为空")
                .hasSize(3);
        assertThat(ctx.recentMessages())
                .as("重建内容应与写入内容一致且时间正序")
                .extracting(MemoryService.Message::content)
                .containsExactly("短会话第1条，用户提到部署在测试环境",
                        "短会话第2条，用户提到部署在测试环境",
                        "短会话第3条，用户提到部署在测试环境");
        assertThat(ctx.recentMessages()).allSatisfy(m -> assertThat(m.id()).isNotNull());
        assertThat(meterRegistry.get("memory.rebuild.total").counter().count())
                .as("T5：短会话回源重建应触发一次")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("T7 部分重建回归：摘要水位线之后的新消息应在 Redis 失效后被精确恢复")
    void t7_partialRebuildAfterWatermark() throws Exception {
        String conv = newConv();
        // 8 条触发摘要（水位线 = 第 8 条 id）
        for (int i = 1; i <= 8; i++) {
            memoryService.addMessage("u1", conv, "user", "已摘要历史第" + i + "条");
        }
        assertThat(await(Duration.ofSeconds(10), () -> generateCalls.get() >= 1 && markerPresent(conv))).isTrue();
        Long watermark = dbMaxMessageId(conv);

        // 摘要后继续对话：2 条新消息进入窗口但不触发新摘要
        memoryService.addMessage("u1", conv, "user", "摘要后新消息A");
        memoryService.addMessage("u1", conv, "user", "摘要后新消息B");
        assertThat(redisMessages(conv)).hasSize(3); // [marker, A, B]

        // Redis 整体失效（消息窗口 + 摘要缓存都丢）
        redis.delete("conversation:messages:" + conv);
        redis.delete("conversation:summary:" + conv);

        MemoryService.ConversationContext ctx = memoryService.getContext("u1", conv);

        System.out.println("[T7] 部分重建：summary 回源=" + (ctx.summary() != null)
                + " | 恢复消息=" + ctx.recentMessages().size()
                + " | 内容=" + ctx.recentMessages().stream().map(MemoryService.Message::content).toList());

        // ===== 契约断言 =====
        assertThat(ctx.summary()).as("摘要应从 MySQL 回源").isNotNull();
        assertThat(ctx.recentMessages())
                .as("T7 部分重建契约：只应恢复水位线之后的 2 条新消息")
                .hasSize(2);
        assertThat(ctx.recentMessages())
                .extracting(MemoryService.Message::content)
                .containsExactly("摘要后新消息A", "摘要后新消息B");
        assertThat(ctx.recentMessages())
                .extracting(MemoryService.Message::id)
                .allSatisfy(id -> assertThat(id).isGreaterThan(watermark));
        assertThat(meterRegistry.get("memory.rebuild.total").counter().count())
                .as("T7：部分重建应触发一次")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("T6 特征记录：clearConversation 的实际清理范围（Redis 全清，MySQL 消息保留）")
    void t6_clearConversationSemantics() throws Exception {
        String conv = newConv();
        // 把阈值降到 2 以便触发一次摘要，完整观察清理范围
        ReflectionTestUtils.setField(memoryService, "summaryThreshold", 2);
        memoryService.addMessage("u1", conv, "user", "清理语义测试1");
        memoryService.addMessage("u1", conv, "user", "清理语义测试2");
        assertThat(await(Duration.ofSeconds(10), () -> redisSummary(conv) != null
                && markerPresent(conv))).isTrue();

        memoryService.clearConversation(conv);

        System.out.println("[T6] clearConversation 后：Redis 消息=" + redisMessages(conv).size()
                + " | Redis 摘要=" + (redisSummary(conv) != null)
                + " | MySQL 消息行数=" + dbMessageCount(conv));

        // 特征断言（记录现状，非缺陷判定）
        assertThat(redisMessages(conv)).as("Redis 消息应被清除").isEmpty();
        assertThat(redisSummary(conv)).as("Redis 摘要应被清除").isNull();
        assertThat(dbMessageCount(conv)).as("现状记录：MySQL 消息行保留（删除会话≠删除数据）").isEqualTo(2);
    }
}
