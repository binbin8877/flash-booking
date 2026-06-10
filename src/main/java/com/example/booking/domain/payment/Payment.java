package com.example.booking.domain.payment;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false, unique = true)
    private Long bookingId;

    @Column(name = "total_amount", nullable = false)
    private int totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "pg_transaction_id", length = 100)
    private String pgTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private Payment(Long bookingId, int totalAmount, PaymentStatus status, String pgTransactionId) {
        this.bookingId = bookingId;
        this.totalAmount = totalAmount;
        this.status = status;
        this.pgTransactionId = pgTransactionId;
        this.createdAt = LocalDateTime.now();
    }

    public void markSuccess(String pgTransactionId) {
        this.status = PaymentStatus.SUCCESS;
        this.pgTransactionId = pgTransactionId;
    }

    public void markFailed() {
        this.status = PaymentStatus.FAILED;
    }
}
