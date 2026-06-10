package com.example.booking.application.booking;

import com.example.booking.application.payment.PaymentCommand;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class BookingCommand {
    String idempotencyKey;
    Long userId;
    Long productId;
    List<PaymentCommand.Line> paymentLines;
}
