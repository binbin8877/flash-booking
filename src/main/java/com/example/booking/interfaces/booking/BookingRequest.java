package com.example.booking.interfaces.booking;

import com.example.booking.application.payment.PaymentCommand;
import com.example.booking.domain.payment.PaymentMethodType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.Map;

public record BookingRequest(
        @NotNull @Positive Long productId,
        @NotNull @Valid PaymentSection payment
) {
    public record PaymentSection(@NotNull @Valid List<LineDto> lines) {}

    public record LineDto(
            @NotNull PaymentMethodType method,
            @Positive int amount,
            Map<String, String> attributes
    ) {
        public PaymentCommand.Line toCommandLine() {
            return PaymentCommand.Line.builder()
                    .method(method)
                    .amount(amount)
                    .attributes(attributes)
                    .build();
        }
    }
}
