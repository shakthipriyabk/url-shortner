package com.example.shortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(String baseUrl, Cache cache, RateLimit rateLimit) {

    /** Positive entries live {@code ttlMinutes}; "not found" markers live {@code negativeTtlSeconds}. */
    public record Cache(long ttlMinutes, long negativeTtlSeconds) {}

    public record RateLimit(Bucket shorten, Bucket redirect) {}

    /** Token bucket: {@code capacity} is the burst, {@code refillPerSecond} the sustained rate. */
    public record Bucket(int capacity, double refillPerSecond) {}
}
