package com.example.booking.domain.booking;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "booking")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 64, unique = true)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "total_amount", nullable = false)
    private int totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Builder
    private Booking(String idempotencyKey, Long userId, Long productId, int totalAmount, BookingStatus status) {
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.productId = productId;
        this.totalAmount = totalAmount;
        this.status = status;
        this.createdAt = LocalDateTime.now();
    }

    public void confirm() {
        this.status = BookingStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
    }
}
