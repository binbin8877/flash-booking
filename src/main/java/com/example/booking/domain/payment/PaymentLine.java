package com.example.booking.domain.payment;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethodType method;

    @Column(nullable = false)
    private int amount;

    @Column(name = "external_ref", length = 100)
    private String externalRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Builder
    private PaymentLine(Long paymentId, PaymentMethodType method, int amount, String externalRef, PaymentStatus status) {
        this.paymentId = paymentId;
        this.method = method;
        this.amount = amount;
        this.externalRef = externalRef;
        this.status = status;
    }

    public void markSuccess(String externalRef) {
        this.externalRef = externalRef;
        this.status = PaymentStatus.SUCCESS;
    }

    public void markFailed() {
        this.status = PaymentStatus.FAILED;
    }
}
