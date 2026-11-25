package com.example.shortener.web;

import java.time.Instant;

public record ShortenResponse(String shortCode, String shortUrl, String originalUrl, Instant createdAt, Instant expiresAt) {
}
