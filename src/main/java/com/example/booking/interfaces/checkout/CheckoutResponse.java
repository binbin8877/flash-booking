package com.example.booking.interfaces.checkout;

import com.example.booking.application.checkout.CheckoutView;

import java.time.LocalDateTime;

public record CheckoutResponse(
        ProductView product,
        UserView user
) {
    public static CheckoutResponse from(CheckoutView view) {
        var product = view.product();
        var user = view.user();
        return new CheckoutResponse(
                new ProductView(
                        product.getId(),
                        product.getName(),
                        product.getPrice(),
                        product.getCheckInAt(),
                        product.getCheckOutAt(),
                        view.remainingStock()
                ),
                new UserView(user.getId(), user.getPointBalance())
        );
    }

    public record ProductView(
            Long id,
            String name,
            int price,
            LocalDateTime checkInAt,
            LocalDateTime checkOutAt,
            int remainingStock
    ) {}

    public record UserView(Long id, int pointBalance) {}
}
