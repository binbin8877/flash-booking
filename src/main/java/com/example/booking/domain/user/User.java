package com.example.booking.domain.user;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "point_balance", nullable = false)
    private int pointBalance;

    @Version
    private long version;

    public void usePoints(int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (pointBalance < amount) {
            throw new IllegalStateException("insufficient point balance");
        }
        this.pointBalance -= amount;
    }
}
