package com.example.booking.domain.product;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false)
    private int price;

    @Column(name = "check_in_at", nullable = false)
    private LocalDateTime checkInAt;

    @Column(name = "check_out_at", nullable = false)
    private LocalDateTime checkOutAt;

    @Column(name = "total_stock", nullable = false)
    private int totalStock;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
