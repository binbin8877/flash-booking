package com.example.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HoldExpirySweeper 의 PG 환불 후보 조회 배치 크기.
 * yml prefix: {@code app.sweeper}.
 */
@ConfigurationProperties(prefix = "app.sweeper")
public record SweeperProperties(int batchSize) {}
