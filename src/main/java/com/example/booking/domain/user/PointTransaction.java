package com.example.booking.domain.user;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "point_transaction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private int delta;

    @Column(nullable = false, length = 40)
    private String reason;

    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private PointTransaction(Long userId, int delta, String reason, Long refId) {
        this.userId = userId;
        this.delta = delta;
        this.reason = reason;
        this.refId = refId;
        this.createdAt = LocalDateTime.now();
    }

    public static PointTransaction use(Long userId, int amount, Long bookingId) {
        return PointTransaction.builder()
                .userId(userId)
                .delta(-amount)
                .reason("BOOKING_USE")
                .refId(bookingId)
                .build();
    }
}
