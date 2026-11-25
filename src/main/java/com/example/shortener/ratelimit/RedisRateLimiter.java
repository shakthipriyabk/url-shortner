package com.example.shortener.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/** Distributed token-bucket rate limiter. All state lives in Redis, so every app instance shares the same limits. */
@Component
public class RedisRateLimiter {

    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
        static Decision failOpen() {
            return new Decision(true, -1, 0);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/token_bucket.lua"), List.class);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Decision tryAcquire(String key, int capacity, double refillPerSecond) {
        try {
            List<?> result = redis.execute(SCRIPT, List.of(key),
                    String.valueOf(capacity), String.valueOf(refillPerSecond), "1");
            if (result == null || result.size() < 3) {
                return Decision.failOpen();
            }
            return new Decision(
                    ((Number) result.get(0)).longValue() == 1L,
                    ((Number) result.get(1)).longValue(),
                    ((Number) result.get(2)).longValue());
        } catch (DataAccessException e) {
            // Fail open: if Redis is down we'd rather serve traffic unthrottled than take the whole service down.
            log.warn("Rate limiter unavailable, allowing request: {}", e.getMessage());
            return Decision.failOpen();
        }
    }
}
