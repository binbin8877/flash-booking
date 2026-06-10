package com.example.booking.application.checkout;

import com.example.booking.domain.product.Product;
import com.example.booking.domain.user.User;

/**
 * 체크아웃 화면용 조립 결과 — Controller 가 응답 DTO 로 다시 매핑한다.
 */
public record CheckoutView(Product product, User user, int remainingStock) {}
