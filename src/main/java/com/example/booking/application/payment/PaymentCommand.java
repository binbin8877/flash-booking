package com.example.booking.application.payment;

import com.example.booking.domain.payment.PaymentMethodType;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class PaymentCommand {
    Long userId;
    Long productId;
    Long bookingId;
    int totalAmount;
    List<Line> lines;

    @Value
    @Builder
    public static class Line {
        PaymentMethodType method;
        int amount;
        Map<String, String> attributes;
    }
}
