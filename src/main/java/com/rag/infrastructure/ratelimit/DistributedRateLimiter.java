package com.rag.infrastructure.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 分布式限流器
 *
 * 基于 Redis 实现滑动窗口限流算法:
 * - 使用 ZSET 存储请求时间戳
 * - Lua 脚本保证原子性
 * - 支持多维度限流 (用户、IP、接口、知识库)
 */
@Component
public class DistributedRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(DistributedRateLimiter.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * 限流配置
     */
    @Value("${rate-limit.endpoint.limit:60}")
    private int endpointLimit;

    @Value("${rate-limit.endpoint.window-seconds:60}")
    private int endpointWindowSeconds;

    @Value("${rate-limit.user.limit:100}")
    private int userLimit;

    @Value("${rate-limit.user.window-seconds:60}")
    private int userWindowSeconds;

    @Value("${rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    /**
     * 滑动窗口 Lua 脚本
     *
     * KEYS[1]: 限流 key
     * ARGV[1]: 当前时间戳 (毫秒)
     * ARGV[2]: 窗口大小 (毫秒)
     * ARGV[3]: 限流阈值
     *
     * 返回 1 表示允许，返回 0 表示拒绝
     */
    private static final String LUA_SLIDING_WINDOW = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])

            -- 删除窗口外的记录
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

            -- 获取当前窗口内请求数
            local count = redis.call('ZCARD', key)

            if count < limit then
                -- 未超限，添加新请求
                redis.call('ZADD', key, now, now .. ':' .. math.random())
                -- 设置过期时间
                redis.call('PEXPIRE', key, window)
                return 1
            else
                -- 超限，拒绝
                return 0
            end
            """;

    private final DefaultRedisScript<Long> slidingWindowScript;

    public DistributedRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.slidingWindowScript = new DefaultRedisScript<>();
        slidingWindowScript.setScriptText(LUA_SLIDING_WINDOW);
        slidingWindowScript.setResultType(Long.class);
    }

    /**
     * 限流结果
     */
    public record RateLimitResult(boolean allowed, int remaining, long retryAfterMs) {}

    /**
     * 尝试获取限流令牌
     *
     * @param key 限流 key
     * @param limit 限流阈值 (单位时间内的最大请求数)
     * @param windowSeconds 窗口大小 (秒)
     * @return 限流结果
     */
    public RateLimitResult tryAcquire(String key, int limit, int windowSeconds) {
        if (!rateLimitEnabled) {
            return new RateLimitResult(true, limit - 1, 0);
        }

        try {
            long now = System.currentTimeMillis();
            Long result = redisTemplate.execute(
                    slidingWindowScript,
                    Collections.singletonList(key),
                    String.valueOf(now),
                    String.valueOf(windowSeconds * 1000L),
                    String.valueOf(limit)
            );

            boolean allowed = result != null && result == 1;

            if (allowed) {
                // 获取剩余请求数
                Long count = redisTemplate.opsForZSet().zCard(key);
                int remaining = Math.max(0, limit - (count != null ? count.intValue() : 1));
                return new RateLimitResult(true, remaining, 0);
            } else {
                // 计算重试时间 (窗口大小)
                return new RateLimitResult(false, 0, windowSeconds * 1000L);
            }

        } catch (Exception e) {
            log.error("Rate limit check failed, allowing request", e);
            // 失败时默认放行
            return new RateLimitResult(true, limit - 1, 0);
        }
    }

    /**
     * 接口限流 (按端点和 IP)
     *
     * @param endpoint 接口路径
     * @param clientIp 客户端 IP
     * @return 限流结果
     */
    public RateLimitResult tryAcquireEndpoint(String endpoint, String clientIp) {
        String key = "rate:endpoint:" + endpoint + ":" + clientIp;
        return tryAcquire(key, endpointLimit, endpointWindowSeconds);
    }

    /**
     * 用户限流
     *
     * @param userId 用户 ID
     * @return 限流结果
     */
    public RateLimitResult tryAcquireUser(String userId) {
        String key = "rate:user:" + userId;
        return tryAcquire(key, userLimit, userWindowSeconds);
    }

    /**
     * 知识库限流
     *
     * @param kbId 知识库 ID
     * @param limit 自定义限流阈值
     * @param windowSeconds 窗口大小
     * @return 限流结果
     */
    public RateLimitResult tryAcquireKnowledgeBase(String kbId, int limit, int windowSeconds) {
        String key = "rate:kb:" + kbId;
        return tryAcquire(key, limit, windowSeconds);
    }

    /**
     * 检查是否允许请求，如果不允许则抛出异常
     */
    public void checkEndpoint(String endpoint, String clientIp) {
        RateLimitResult result = tryAcquireEndpoint(endpoint, clientIp);
        if (!result.allowed()) {
            log.warn("Rate limit exceeded for endpoint: {}, clientIp: {}", endpoint, clientIp);
            throw new RateLimitExceededException(
                    "Rate limit exceeded for endpoint: " + endpoint,
                    result.retryAfterMs()
            );
        }
    }

    /**
     * 检查用户请求是否允许
     */
    public void checkUser(String userId) {
        RateLimitResult result = tryAcquireUser(userId);
        if (!result.allowed()) {
            log.warn("Rate limit exceeded for user: {}", userId);
            throw new RateLimitExceededException(
                    "Rate limit exceeded for user: " + userId,
                    result.retryAfterMs()
            );
        }
    }

    /**
     * 限流超限异常
     */
    public static class RateLimitExceededException extends RuntimeException {
        private final long retryAfterMs;

        public RateLimitExceededException(String message, long retryAfterMs) {
            super(message);
            this.retryAfterMs = retryAfterMs;
        }

        public long getRetryAfterMs() {
            return retryAfterMs;
        }
    }
}
