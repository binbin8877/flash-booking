package com.example.booking.infrastructure.persistence;

import com.example.booking.domain.booking.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    Optional<Booking> findByIdempotencyKey(String idempotencyKey);
}
