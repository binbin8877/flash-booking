package com.example.booking.infrastructure.persistence;

import com.example.booking.domain.product.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
