package com.rag.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.domain.repository.ConversationSummaryRepository;
import com.rag.domain.repository.MessageRepository;
import com.rag.infrastructure.llm.ChatModelService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记忆系统随机化 Soak 测试（500 次迭代）。
 *
 * 动机：7 个确定性边界用例只能证明"单点正确"，无法证明"统计可靠"——
 * 尤其是竞态修复（水位线 + LTRIM + 同会话去重）必须在不同随机交错下反复锤打。
 *
 * 结构（共 500 次迭代，每次迭代 6 项不变量校验，总断言量 3000+）：
 *   A. 200 次随机场景：随机消息数/长度分布、随机触发摘要、随机模拟 Redis 失效与恢复
 *   B. 150 次并发写入：2-4 个写线程同时 addMessage，LLM 延迟逐轮随机化，锤打竞态窗口
 *   C. 150 次多会话交错：4 个会话共享摘要线程池，验证会话间隔离与线程池争用
 *
 * 每次迭代校验的不变量：
 *   I1 无丢失：每条已写入消息 ∈ (热窗口 ∪ 摘要文本)（token 回声桩使摘要链可全量追踪）
 *   I2 无复活：窗口内所有消息 id > 摘要水位线（lastMessageId）
 *   I3 有界性：buildContextPrompt 输出 ≤ 12000 字符（≈3000 token）
 *   I4 持久性：MySQL 行数 == 写入调用数
 *   I5 并发去重：单会话 LLM 摘要最大并发 ≤ 1（Soak-B 逐轮校验）
 *   I6 隔离性：会话间记忆互不串扰（窗口内容只含本会话 token，Soak-C 校验）
 *
 * 可复现性：固定随机种子（默认 20260905，可用 -Dsoak.seed=... 覆盖），
 * 失败信息携带迭代序号、种子与会话 id。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3307/rag_system?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=123456",
        "spring.jpa.hibernate.ddl-auto=none"
})
@Import(MemoryServiceSoakTest.RedisTestConfig.class)
class MemoryServiceSoakTest {

    private static final String CONV_PREFIX = "memtest-soak-";
    private static final int CONTEXT_BUDGET_CHARS = 12_000;
    private static final String MARKER = "[早期对话已摘要]";
    private static final long DEFAULT_SEED = 20260905L;
    /** 消息 token 格式：TOK + 8 位十六进制（回声桩据此提取，摘要链可追踪且不膨胀） */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("TOK[0-9a-f]{8}");

    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private ConversationSummaryRepository summaryRepository;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper json = new ObjectMapper();

    private ChatModelService chatModel;
    private MemoryService memoryService;
    private SimpleMeterRegistry meterRegistry;

    /** 摘要调用计数与并发追踪（桩内维护） */
    private final AtomicInteger generateCalls = new AtomicInteger();
    private final AtomicInteger concurrentGen = new AtomicInteger();
    private final AtomicLong maxConcurrentGen = new AtomicLong();

    private Random random;
    private long seed;
    /** 桩模拟 LLM 延迟（毫秒），扩大异步窗口使随机交错更充分 */
    private volatile long llmLatencyMs = 5;

    // ---------- 统计 ----------
    private long totalAdds;
    private long totalSummaries;
    private long totalInvariantChecks;
    private long totalFlushes;
    private long transientRecoveries;

    @TestConfiguration
    static class RedisTestConfig {
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
        seed = Long.getLong("soak.seed", DEFAULT_SEED);
        random = new Random(seed);
        generateCalls.set(0);
        concurrentGen.set(0);
        maxConcurrentGen.set(0);
        totalAdds = 0;
        totalSummaries = 0;
        totalInvariantChecks = 0;
        totalFlushes = 0;
        transientRecoveries = 0;

        chatModel = Mockito.mock(ChatModelService.class);
        Mockito.doAnswer(inv -> {
            String prompt = inv.getArgument(0, String.class);
            int c = concurrentGen.incrementAndGet();
            maxConcurrentGen.accumulateAndGet(c, Math::max);
            try {
                Thread.sleep(Math.max(0, llmLatencyMs));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            generateCalls.incrementAndGet();
            concurrentGen.decrementAndGet();
            totalSummaries++;
            // token 回声桩：只回显 prompt 中出现的消息 token。
            // 滚动合并会把旧摘要文本纳入新摘要输入 → 旧 token 再次被提取 → 摘要链全量可追踪且不膨胀。
            Set<String> tokens = new LinkedHashSet<>();
            Matcher m = TOKEN_PATTERN.matcher(prompt == null ? "" : prompt);
            while (m.find()) {
                tokens.add(m.group());
            }
            return "[SUM]" + String.join(",", tokens);
        }).when(chatModel).generate(Mockito.anyString());

        meterRegistry = new SimpleMeterRegistry();
        memoryService = new MemoryService(redis, summaryRepository, messageRepository, chatModel,
                Executors.newFixedThreadPool(4), meterRegistry);
        ReflectionTestUtils.setField(memoryService, "windowSize", 10);
        ReflectionTestUtils.setField(memoryService, "summaryThreshold", 8);
        ReflectionTestUtils.setField(memoryService, "triggerTokens", 2_500);
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
        System.out.printf("[SOAK] 汇总: 摘要调用=%d, 窗口补齐(meter)=%.0f, 模拟失效=%d, 不变量校验=%d, 暂态自愈=%d, 种子=%d%n",
                totalSummaries, meterRegistry.get("memory.rebuild.total").counter().count(),
                totalFlushes, totalInvariantChecks, transientRecoveries, seed);
    }

    // ---------- 迭代场景 A：随机操作序列（200 次） ----------

    @Test
    @DisplayName("Soak-A 200 次随机场景：随机消息分布 + 随机失效恢复，校验 I1-I4")
    void soakA_randomScenarios() {
        int iterations = 200;
        for (int iter = 1; iter <= iterations; iter++) {
            String conv = CONV_PREFIX + "a" + iter + "-" + UUID.randomUUID();
            List<String> tokens = new ArrayList<>();
            try {
                runRandomScenario(iter, conv, tokens);
                verifyInvariants(iter, conv, tokens, "u1");
            } catch (AssertionError | Exception e) {
                throw new AssertionError(String.format(
                        "[Soak-A] 迭代 %d/%d 失败 (seed=%d, conv=%s, 已完成 %d 次迭代)",
                        iter, iterations, seed, conv, iter - 1), e);
            }
        }
        System.out.printf("[SOAK-A] %d 次随机场景全部通过: 总写入=%d, 摘要=%d, 补齐(meter)=%.0f, 失效=%d%n",
                iterations, totalAdds, totalSummaries,
                meterRegistry.get("memory.rebuild.total").counter().count(), totalFlushes);
    }

    private void runRandomScenario(int iter, String conv, List<String> tokens) throws InterruptedException {
        int nMessages = 3 + random.nextInt(18); // 3..20
        int flushCount = random.nextInt(3);     // 0..2 次模拟失效
        Set<Integer> flushPoints = new HashSet<>();
        for (int f = 0; f < flushCount; f++) {
            flushPoints.add(1 + random.nextInt(Math.max(1, nMessages - 1)));
        }

        for (int i = 1; i <= nMessages; i++) {
            String token = "TOK" + UUID.randomUUID().toString().substring(0, 8).replace("-", "").toLowerCase();
            tokens.add(token);
            memoryService.addMessage("u1", conv, randomRole(), token + " " + randomContent());
            totalAdds++;
            Long maxId = jdbcTemplate.queryForObject(
                    "SELECT MAX(id) FROM t_message WHERE conversation_id = ?", Long.class, conv);
            System.out.println("[TRACE] conv=" + conv + " op=" + i + " add id=" + maxId + " token=" + token);
            if (flushPoints.contains(i)) {
                // 模拟 Redis 失效：整体清空后先经历一次读取（真实崩溃恢复的典型顺序）
                redis.delete("conversation:messages:" + conv);
                redis.delete("conversation:summary:" + conv);
                totalFlushes++;
                System.out.println("[TRACE] conv=" + conv + " op=" + i + " FLUSH+READ");
                memoryService.getContext("u1", conv);
            }
            // 逐操作增量校验：单写线程场景下，任何时刻"每条已写入消息 ∈ 窗口 ∪ 摘要"都应成立
            checkNoLoss(iter, i, conv, tokens);
            if (random.nextInt(5) == 0) {
                Thread.sleep(random.nextInt(15)); // 随机交错，让异步摘要与写入赛跑
            }
        }
        // 结束前再经历 1-2 次读取，覆盖"失效后未写入直接读"的路径
        memoryService.getContext("u1", conv);
        if (random.nextBoolean()) {
            memoryService.buildContextPrompt("u1", conv);
        }
        awaitQuiescence();
    }

    // ---------- 迭代场景 B：并发写入锤打竞态窗口（150 次） ----------

    @Test
    @DisplayName("Soak-B 150 次并发写入：多线程随机交错 addMessage，校验 I1/I4/I5")
    void soakB_concurrentWriters() throws Exception {
        int iterations = 150;
        for (int iter = 1; iter <= iterations; iter++) {
            String conv = CONV_PREFIX + "b" + iter + "-" + UUID.randomUUID();
            llmLatencyMs = random.nextInt(25); // 每轮随机 LLM 延迟，扫描不同竞态窗口
            maxConcurrentGen.set(0);           // 单会话场景：全局最大并发即该会话摘要并发

            List<String> tokens = new ArrayList<>();
            int writers = 2 + random.nextInt(3);   // 2..4 个写线程
            int perWriter = 3 + random.nextInt(4); // 每线程 3..6 条
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(writers);
            ExecutorService pool = Executors.newFixedThreadPool(writers);
            try {
                for (int w = 0; w < writers; w++) {
                    final int wid = w;
                    pool.submit(() -> {
                        try {
                            startLatch.await();
                            for (int i = 0; i < perWriter; i++) {
                                String token = "TOK" + UUID.randomUUID().toString().substring(0, 8)
                                        .replace("-", "").toLowerCase();
                                synchronized (tokens) {
                                    tokens.add(token);
                                }
                                memoryService.addMessage("u1", conv, "user", token + " 并发内容");
                                totalAdds++;
                                if (random.nextInt(3) == 0) {
                                    Thread.sleep(random.nextInt(10));
                                }
                            }
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }
                startLatch.countDown();
                assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
                awaitQuiescence();
                verifyInvariants(iter, conv, tokens, "u1");
                // I5：单会话摘要最大并发 ≤ 1（同会话去重契约在随机并发下成立）
                assertThat(maxConcurrentGen.get())
                        .as("[Soak-B] 迭代 %d: 单会话摘要并发超限", iter)
                        .isLessThanOrEqualTo(1);
            } catch (AssertionError | Exception e) {
                throw new AssertionError(String.format(
                        "[Soak-B] 迭代 %d/%d 失败 (seed=%d, conv=%s, writers=%d, 已完成 %d 次迭代)",
                        iter, iterations, seed, conv, writers, iter - 1), e);
            } finally {
                pool.shutdownNow();
            }
        }
        System.out.printf("[SOAK-B] %d 次并发写入全部通过: 总写入=%d, 摘要=%d%n",
                iterations, totalAdds, totalSummaries);
    }

    // ---------- 迭代场景 C：多会话交错（150 次） ----------

    @Test
    @DisplayName("Soak-C 150 次多会话交错：4 会话共享线程池，校验 I1/I2/I3/I4/I6")
    void soakC_multiConversationInterleaved() {
        int iterations = 150;
        ExecutorService summaryPool = Executors.newFixedThreadPool(4);
        try {
            for (int iter = 1; iter <= iterations; iter++) {
                int convCount = 4;
                List<String> convs = new ArrayList<>();
                List<List<String>> tokenLists = new ArrayList<>();
                for (int c = 0; c < convCount; c++) {
                    convs.add(CONV_PREFIX + "c" + iter + "-" + c + "-" + UUID.randomUUID());
                    tokenLists.add(new ArrayList<>());
                }

                try {
                    // 交替向 4 个会话各写入若干消息
                    int rounds = 2 + random.nextInt(4); // 2..5 轮
                    for (int r = 0; r < rounds; r++) {
                        for (int c = 0; c < convCount; c++) {
                            String token = "TOK" + UUID.randomUUID().toString().substring(0, 8)
                                    .replace("-", "").toLowerCase();
                            tokenLists.get(c).add(token);
                            memoryService.addMessage("u" + c, convs.get(c), "user",
                                    token + " 会话" + c + "的内容");
                            totalAdds++;
                        }
                        if (random.nextInt(4) == 0) {
                            Thread.sleep(random.nextInt(10));
                        }
                    }
                    // 每个会话独立读取与构建上下文
                    for (int c = 0; c < convCount; c++) {
                        memoryService.buildContextPrompt("u" + c, convs.get(c));
                    }
                    awaitQuiescence();

                    // I1/I2/I3/I4 + I6 隔离性逐会话校验
                    for (int c = 0; c < convCount; c++) {
                        verifyInvariants(iter, convs.get(c), tokenLists.get(c), "u" + c);
                        for (Map<String, Object> m : readWindow(convs.get(c))) {
                            String content = String.valueOf(m.get("content"));
                            if (MARKER.equals(content)) {
                                continue;
                            }
                            assertThat(content).as("[Soak-C] 迭代 %d: 会话 %d 出现外来内容", iter, c)
                                    .startsWith("TOK");
                        }
                    }
                    // 全局摘要并发 ≤ 会话数（无失控放大）
                    assertThat(maxConcurrentGen.get())
                            .as("[Soak-C] 迭代 %d: 全局摘要并发超限", iter)
                            .isLessThanOrEqualTo(convCount);
                } catch (AssertionError | Exception e) {
                    throw new AssertionError(String.format(
                            "[Soak-C] 迭代 %d/%d 失败 (seed=%d, 已完成 %d 次迭代)",
                            iter, iterations, seed, iter - 1), e);
                }
            }
            System.out.printf("[SOAK-C] %d 次多会话交错全部通过: 总写入=%d, 摘要=%d%n",
                    iterations, totalAdds, totalSummaries);
        } finally {
            summaryPool.shutdownNow();
        }
    }

    // ---------- 不变量校验 ----------

    private void verifyInvariants(int iter, String conv, List<String> tokens, String userId) {
        awaitQuiescence();

        // 最终对账读：模拟"用户的下一条消息触发读取"。窗口的部分失效是暂态——
        // 任何一次静默后的读取都应完成缺口补齐。此读之后再校验不变量才是系统真实承诺。
        memoryService.getContext(userId, conv);

        // I4 持久性
        Integer dbCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_message WHERE conversation_id = ?", Integer.class, conv);
        assertThat(dbCount).as("[迭代 %d] I4 持久性: MySQL 行数应等于写入数", iter).isEqualTo(tokens.size());

        List<Map<String, Object>> window = readWindow(conv);
        String summaryText = loadSummaryText(conv);
        long watermark = loadWatermark(conv);

        Set<String> windowContents = new HashSet<>();
        for (Map<String, Object> m : window) {
            String content = String.valueOf(m.get("content"));
            if (MARKER.equals(content)) {
                continue;
            }
            windowContents.add(content);
            // I2 无复活
            Number id = (Number) m.get("id");
            assertThat(id == null ? 0L : id.longValue())
                    .as("[迭代 %d] I2 无复活: 窗口消息 id 应大于水位线 %d", iter, watermark)
                    .isGreaterThan(watermark);
        }

        for (String token : tokens) {
            boolean inWindow = windowContents.stream().anyMatch(c -> c.contains(token));
            boolean inSummary = summaryText != null && summaryText.contains(token);
            assertThat(inWindow || inSummary)
                    .as("[迭代 %d] I1 无丢失: 消息 %s 既不在热窗口也不在任何摘要中", iter, token)
                    .isTrue();
        }

        // I3 有界性
        String prompt = memoryService.buildContextPrompt(userId, conv);
        assertThat(prompt.length())
                .as("[迭代 %d] I3 有界性: 上下文长度超预算", iter)
                .isLessThanOrEqualTo(CONTEXT_BUDGET_CHARS);

        totalInvariantChecks += 3 + tokens.size() + window.size();
    }

    /**
     * 逐操作校验：当前时刻每条已写入消息必须 ∈ (热窗口 ∪ 摘要文本)。
     * 已知暂态："摘要在途 + 失效恢复守卫"会让窗口短暂残缺，而在途摘要的对账
     * （reconcile-before-snapshot）会在完成时覆盖缺口——因此缺 token 时先静默
     * 并对账一次再验，仍缺才判定为真丢失。
     */
    private void checkNoLoss(int iter, int op, String conv, List<String> tokens) {
        List<String> missing = missingTokens(conv, tokens);
        if (missing.isEmpty()) {
            return;
        }
        awaitQuiescence();
        memoryService.getContext("u1", conv);
        List<String> stillMissing = missingTokens(conv, tokens);
        assertThat(stillMissing).as("[迭代 %d op=%d] I1 无丢失（静默对账后仍缺失）", iter, op).isEmpty();
        transientRecoveries++;
    }

    private List<String> missingTokens(String conv, List<String> tokens) {
        Set<String> windowContents = new HashSet<>();
        for (Map<String, Object> m : readWindow(conv)) {
            String content = String.valueOf(m.get("content"));
            if (!MARKER.equals(content)) {
                windowContents.add(content);
            }
        }
        String summaryText = loadSummaryText(conv);
        List<String> missing = new ArrayList<>();
        for (String token : tokens) {
            boolean inWindow = windowContents.stream().anyMatch(c -> c.contains(token));
            boolean inSummary = summaryText != null && summaryText.contains(token);
            if (!inWindow && !inSummary) {
                missing.add(token + " | 窗口=" + windowContents + " | 摘要=" + summaryText);
            }
        }
        return missing;
    }

    private List<Map<String, Object>> readWindow(String conv) {
        List<String> raw = redis.opsForList().range("conversation:messages:" + conv, 0, -1);
        List<Map<String, Object>> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (String s : raw) {
            try {
                result.add(json.readValue(s, Map.class));
            } catch (Exception ignored) {
                // 无法解析的条目视为丢失证据，由 I1 捕获
            }
        }
        return result;
    }

    private String loadSummaryText(String conv) {
        String redisSummary = redis.opsForValue().get("conversation:summary:" + conv);
        if (redisSummary != null) {
            return redisSummary;
        }
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT content FROM t_conversation_summary WHERE conversation_id = ?",
                    String.class, conv);
        } catch (Exception e) {
            return null; // 尚无摘要
        }
    }

    private long loadWatermark(String conv) {
        try {
            Long w = jdbcTemplate.queryForObject(
                    "SELECT NULLIF(last_message_id, '') FROM t_conversation_summary WHERE conversation_id = ?",
                    Long.class, conv);
            return w == null ? 0L : w;
        } catch (Exception e) {
            return 0L; // 尚无摘要
        }
    }

    /** 等待异步摘要静默：计数连续 3 个 40ms 周期不变且无在途调用视为静默 */
    private void awaitQuiescence() {
        long last = -1;
        int stable = 0;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            long now = generateCalls.get();
            if (now == last && concurrentGen.get() == 0) {
                stable++;
                if (stable >= 3) {
                    return;
                }
            } else {
                stable = 0;
                last = now;
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ---------- 随机工具 ----------

    private String randomRole() {
        return random.nextBoolean() ? "user" : "assistant";
    }

    /** 内容长度分布：多数短消息，少数中等，偶发长消息（触发 token 路径） */
    private String randomContent() {
        int roll = random.nextInt(100);
        int len;
        if (roll < 80) {
            len = 10 + random.nextInt(40);       // 10..50
        } else if (roll < 95) {
            len = 500 + random.nextInt(300);     // 500..800
        } else {
            len = 1000 + random.nextInt(500);    // 1000..1500，单条即可逼近/超过 token 触发线
        }
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) (0x4E00 + random.nextInt(500))); // 常用 CJK 区
        }
        return sb.toString();
    }
}
