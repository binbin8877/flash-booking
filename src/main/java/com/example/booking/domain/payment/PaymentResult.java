package com.example.booking.domain.payment;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PaymentResult {
    boolean success;
    String externalRef;
    String failureReason;

    public static PaymentResult success(String externalRef) {
        return PaymentResult.builder().success(true).externalRef(externalRef).build();
    }

    public static PaymentResult failure(String reason) {
        return PaymentResult.builder().success(false).failureReason(reason).build();
    }
}
