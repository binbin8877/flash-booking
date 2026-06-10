package com.example.booking.application.payment;

import com.example.booking.domain.payment.Payment;
import com.example.booking.domain.payment.PaymentLine;
import lombok.Getter;

import java.util.List;

@Getter
public class PaymentFailedException extends RuntimeException {

    private final String reason;
    private final Payment payment;
    private final List<PaymentLine> lines;

    public PaymentFailedException(String reason, Payment payment, List<PaymentLine> lines) {
        super("payment failed: " + reason);
        this.reason = reason;
        this.payment = payment;
        this.lines = lines;
    }
}
