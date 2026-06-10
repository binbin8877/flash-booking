package com.example.booking.domain.product;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "product_inventory")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductInventory {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "remaining_stock", nullable = false)
    private int remainingStock;

    @Version
    private long version;

    public void decrement() {
        if (remainingStock <= 0) {
            throw new IllegalStateException("out of stock");
        }
        this.remainingStock -= 1;
    }
}
