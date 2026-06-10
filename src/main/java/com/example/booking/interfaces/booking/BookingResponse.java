package com.example.booking.interfaces.booking;

import com.example.booking.application.booking.BookingResult;

import java.time.LocalDateTime;
import java.util.List;

public record BookingResponse(
        Long bookingId,
        String status,
        LocalDateTime confirmedAt,
        PaymentSummary payment
) {
    public static BookingResponse from(BookingResult result) {
        return new BookingResponse(
                result.getBookingId(),
                result.getStatus().name(),
                result.getConfirmedAt(),
                new PaymentSummary(
                        result.getTotalAmount(),
                        result.getLines().stream()
                                .map(l -> new LineView(l.getMethod(), l.getAmount(), l.getExternalRef()))
                                .toList()
                )
        );
    }

    public record PaymentSummary(int totalAmount, List<LineView> lines) {}

    public record LineView(String method, int amount, String externalRef) {}
}
