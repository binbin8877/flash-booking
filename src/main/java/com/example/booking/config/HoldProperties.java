package com.example.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis hold 키 TTL + sweeper 실행 주기.
 * yml prefix: {@code app.hold}.
 */
@ConfigurationProperties(prefix = "app.hold")
public record HoldProperties(int ttlSeconds, String sweepCron) {}
