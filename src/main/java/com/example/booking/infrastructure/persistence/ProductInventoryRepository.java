package com.example.booking.infrastructure.persistence;

import com.example.booking.domain.product.ProductInventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductInventoryRepository extends JpaRepository<ProductInventory, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ProductInventory i where i.productId = :productId")
    Optional<ProductInventory> findForUpdate(@Param("productId") Long productId);
}
