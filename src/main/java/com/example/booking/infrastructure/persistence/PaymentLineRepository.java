package com.example.booking.infrastructure.persistence;

import com.example.booking.domain.payment.PaymentLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentLineRepository extends JpaRepository<PaymentLine, Long> {
    List<PaymentLine> findByPaymentId(Long paymentId);
}
