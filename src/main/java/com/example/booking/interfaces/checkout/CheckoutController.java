package com.example.booking.interfaces.checkout;

import com.example.booking.application.checkout.CheckoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/checkout")
@RequiredArgsConstructor
@Tag(name = "Checkout", description = "주문서 진입 — 상품/잔여 재고/사용자 포인트 조회")
public class CheckoutController {

    private final CheckoutService checkoutService;

    @GetMapping
    @Operation(summary = "주문서 조회",
            description = "상품 정보, 잔여 재고, 사용자 포인트를 반환합니다. " +
                    "결제수단 종류와 조합 규칙은 백엔드 검증 (POST /bookings) 의 단일 진실원천이며 본 응답에 포함하지 않습니다.")
    public CheckoutResponse get(
            @RequestParam("productId") @Positive long productId,
            @RequestHeader("X-User-Id") @Positive long userId
    ) {
        return CheckoutResponse.from(checkoutService.load(productId, userId));
    }
}
