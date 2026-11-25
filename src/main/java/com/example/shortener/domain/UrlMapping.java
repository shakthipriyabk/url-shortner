package com.example.shortener.domain;

import java.time.Instant;

public record UrlMapping(long id, String shortCode, String originalUrl, Instant createdAt, Instant expiresAt) {

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
