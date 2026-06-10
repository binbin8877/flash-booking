package com.example.booking.domain.payment;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "pg_call_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PgCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "external_ref", nullable = false, length = 100)
    private String externalRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PgCallLogStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Builder
    private PgCallLog(Long bookingId, Long productId, Long userId, String externalRef, PgCallLogStatus status) {
        this.bookingId = bookingId;
        this.productId = productId;
        this.userId = userId;
        this.externalRef = externalRef;
        this.status = status;
        this.createdAt = LocalDateTime.now();
    }

    public void markReconciled() {
        this.status = PgCallLogStatus.RECONCILED;
        this.processedAt = LocalDateTime.now();
    }

    public void markRefunded() {
        this.status = PgCallLogStatus.REFUNDED;
        this.processedAt = LocalDateTime.now();
    }

    public void markRefundFailed() {
        this.status = PgCallLogStatus.REFUND_FAILED;
        this.processedAt = LocalDateTime.now();
    }
}
