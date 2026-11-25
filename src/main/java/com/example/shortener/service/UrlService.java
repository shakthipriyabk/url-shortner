package com.example.shortener.service;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.Base62;
import com.example.shortener.domain.UrlMapping;
import com.example.shortener.repository.UrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class UrlService {

    private static final Logger log = LoggerFactory.getLogger(UrlService.class);

    /** Sentinel cached for codes that don't exist, so repeated lookups of junk codes never reach the database. */
    static final String MISSING = "__missing__";

    /** Anything that can't be a code we issued is rejected before touching Redis or Postgres. */
    private static final Pattern CODE_FORMAT = Pattern.compile("^[0-9A-Za-z]{1,11}$");

    private final UrlRepository repository;
    private final StringRedisTemplate redis;
    private final ShortenerProperties props;

    public UrlService(UrlRepository repository, StringRedisTemplate redis, ShortenerProperties props) {
        this.repository = repository;
        this.redis = redis;
        this.props = props;
    }

    // ---------------------------------------------------------------- write path

    public UrlMapping shorten(String rawUrl, Integer ttlDays) {
        String url = validate(rawUrl);

        long id = repository.nextId();
        String code = Base62.encode(id);
        Instant now = Instant.now();
        Instant expiresAt = ttlDays == null ? null : now.plus(Duration.ofDays(ttlDays));

        UrlMapping mapping = new UrlMapping(id, code, url, now, expiresAt);
        repository.insert(mapping);

        // Write-through: the first click is already a cache hit, and it overwrites any stale "missing" marker.
        cachePut(mapping);
        return mapping;
    }

    // ----------------------------------------------------------------- read path

    /** Cache-aside lookup: Redis first, Postgres on a miss, then populate Redis. */
    public Optional<String> resolve(String code) {
        if (!CODE_FORMAT.matcher(code).matches()) {
            return Optional.empty();
        }

        String key = urlKey(code);
        String cached = cacheGet(key);
        if (cached != null) {
            return MISSING.equals(cached) ? Optional.empty() : Optional.of(cached);
        }

        Optional<UrlMapping> found = repository.findByCode(code).filter(m -> !m.isExpired(Instant.now()));
        if (found.isEmpty()) {
            cacheSet(key, MISSING, Duration.ofSeconds(props.cache().negativeTtlSeconds()));
            return Optional.empty();
        }

        cachePut(found.get());
        return Optional.of(found.get().originalUrl());
    }

    public Optional<UrlMapping> find(String code) {
        if (!CODE_FORMAT.matcher(code).matches()) {
            return Optional.empty();
        }
        return repository.findByCode(code).filter(m -> !m.isExpired(Instant.now()));
    }

    // ----------------------------------------------------------------- analytics

    /** Cheap click counter in Redis. Best effort: losing a few increments is acceptable for analytics. */
    public void recordClick(String code) {
        try {
            redis.opsForValue().increment(clicksKey(code));
        } catch (DataAccessException e) {
            log.warn("Could not record click for {}: {}", code, e.getMessage());
        }
    }

    public long clicks(String code) {
        try {
            String value = redis.opsForValue().get(clicksKey(code));
            return value == null ? 0 : Long.parseLong(value);
        } catch (DataAccessException | NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------- helpers

    private String validate(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("Malformed URL");
        }
        String scheme = uri.getScheme();
        boolean httpOrHttps = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!httpOrHttps) {
            throw new InvalidUrlException("Only http and https URLs are allowed");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw new InvalidUrlException("URL must have a host and no embedded credentials");
        }
        return trimmed;
    }

    private void cachePut(UrlMapping mapping) {
        Duration ttl = Duration.ofMinutes(props.cache().ttlMinutes());
        if (mapping.expiresAt() != null) {
            Duration remaining = Duration.between(Instant.now(), mapping.expiresAt());
            if (remaining.compareTo(ttl) < 0) {
                ttl = remaining;   // never cache a link past its own expiry
            }
        }
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        cacheSet(urlKey(mapping.shortCode()), mapping.originalUrl(), ttl);
    }

    // Redis is an optimisation, not a dependency: every failure degrades to "go to the database".

    private String cacheGet(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (DataAccessException e) {
            log.warn("Redis read failed, falling back to database: {}", e.getMessage());
            return null;
        }
    }

    private void cacheSet(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (DataAccessException e) {
            log.warn("Redis write failed: {}", e.getMessage());
        }
    }

    private static String urlKey(String code) {
        return "url:" + code;
    }

    private static String clicksKey(String code) {
        return "clicks:" + code;
    }
}
