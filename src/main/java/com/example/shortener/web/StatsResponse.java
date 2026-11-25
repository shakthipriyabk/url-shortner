package com.example.shortener.web;

import java.time.Instant;

public record StatsResponse(String shortCode, String originalUrl, Instant createdAt, Instant expiresAt, long clicks) {
}
