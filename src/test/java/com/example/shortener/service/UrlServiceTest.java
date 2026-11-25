package com.example.shortener.service;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.UrlMapping;
import com.example.shortener.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

    private static final String URL = "https://example.com/some/very/long/path?x=1";

    @Mock UrlRepository repository;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> ops;

    UrlService service;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(ops);
        var props = new ShortenerProperties(
                "http://localhost:8080",
                new ShortenerProperties.Cache(60, 60),
                new ShortenerProperties.RateLimit(
                        new ShortenerProperties.Bucket(10, 0.5),
                        new ShortenerProperties.Bucket(100, 50)));
        service = new UrlService(repository, redis, props);
    }

    private static UrlMapping mapping(Instant expiresAt) {
        return new UrlMapping(1, "abc", URL, Instant.now(), expiresAt);
    }

    @Test
    void cacheHitSkipsTheDatabase() {
        when(ops.get("url:abc")).thenReturn(URL);

        assertEquals(Optional.of(URL), service.resolve("abc"));
        verifyNoInteractions(repository);
    }

    @Test
    void cacheMissLoadsFromDatabaseAndPopulatesCache() {
        when(ops.get("url:abc")).thenReturn(null);
        when(repository.findByCode("abc")).thenReturn(Optional.of(mapping(null)));

        assertEquals(Optional.of(URL), service.resolve("abc"));
        verify(ops).set(eq("url:abc"), eq(URL), eq(Duration.ofMinutes(60)));
    }

    @Test
    void unknownCodeIsNegativeCached() {
        when(ops.get("url:abc")).thenReturn(null);
        when(repository.findByCode("abc")).thenReturn(Optional.empty());

        assertTrue(service.resolve("abc").isEmpty());
        verify(ops).set("url:abc", UrlService.MISSING, Duration.ofSeconds(60));
    }

    @Test
    void negativeCacheEntryShortCircuits() {
        when(ops.get("url:abc")).thenReturn(UrlService.MISSING);

        assertTrue(service.resolve("abc").isEmpty());
        verifyNoInteractions(repository);
    }

    @Test
    void expiredLinkIsTreatedAsMissing() {
        when(ops.get("url:abc")).thenReturn(null);
        when(repository.findByCode("abc")).thenReturn(Optional.of(mapping(Instant.now().minusSeconds(5))));

        assertTrue(service.resolve("abc").isEmpty());
    }

    @Test
    void malformedCodeNeverTouchesRedisOrDatabase() {
        assertTrue(service.resolve("../etc/passwd").isEmpty());
        verifyNoInteractions(repository, ops);
    }

    @Test
    void redisFailureFallsBackToDatabase() {
        when(ops.get("url:abc")).thenThrow(new RedisConnectionFailureException("down"));
        when(repository.findByCode("abc")).thenReturn(Optional.of(mapping(null)));

        assertEquals(Optional.of(URL), service.resolve("abc"));
    }

    @Test
    void shortenDerivesCodeFromSequenceId() {
        when(repository.nextId()).thenReturn(916_132_832L);

        UrlMapping m = service.shorten(URL, null);

        assertEquals("100000", m.shortCode());
        verify(repository).insert(any(UrlMapping.class));
        verify(ops).set(eq("url:100000"), eq(URL), any(Duration.class));
    }

    @Test
    void shortenRejectsUnsafeOrMalformedUrls() {
        for (String bad : new String[]{"ftp://example.com/a", "javascript:alert(1)", "not a url",
                "https://user:pass@example.com/", "http:///nohost"}) {
            assertThrows(InvalidUrlException.class, () -> service.shorten(bad, null), bad);
        }
    }
}
