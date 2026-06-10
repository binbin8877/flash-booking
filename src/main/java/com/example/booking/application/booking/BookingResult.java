package com.example.booking.application.booking;

import com.example.booking.domain.booking.BookingStatus;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

@Value
@Builder
public class BookingResult {
    Long bookingId;
    BookingStatus status;
    LocalDateTime confirmedAt;
    int totalAmount;
    List<Line> lines;
    boolean reconstructed;

    @Value
    @Builder
    public static class Line {
        String method;
        int amount;
        String externalRef;
    }
}
