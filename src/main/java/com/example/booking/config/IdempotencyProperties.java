package com.example.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 멱등 캐시 TTL.
 * yml prefix: {@code app.idempotency}.
 */
@ConfigurationProperties(prefix = "app.idempotency")
public record IdempotencyProperties(int ttlSeconds) {}
