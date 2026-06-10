package com.example.booking.infrastructure.persistence;

import com.example.booking.domain.user.PointTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointTransactionRepository extends JpaRepository<PointTransaction, Long> {
}
