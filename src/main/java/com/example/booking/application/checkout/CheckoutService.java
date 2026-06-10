package com.example.booking.application.checkout;

import com.example.booking.domain.product.Product;
import com.example.booking.domain.user.User;
import com.example.booking.infrastructure.persistence.ProductInventoryRepository;
import com.example.booking.infrastructure.persistence.ProductRepository;
import com.example.booking.infrastructure.persistence.UserRepository;
import com.example.booking.infrastructure.redis.StockCounter;
import com.example.booking.interfaces.common.ApiException;
import com.example.booking.interfaces.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CheckoutService {

    private final ProductRepository productRepository;
    private final ProductInventoryRepository inventoryRepository;
    private final UserRepository userRepository;
    private final StockCounter stockCounter;

    public CheckoutView load(long productId, long userId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        int remainingStock = readRemainingStock(productId);

        return new CheckoutView(product, user, remainingStock);
    }

    /**
     * Redis 우선 조회. Redis 다운 또는 키 누락 시 DB inventory 로 폴백.
     */
    private int readRemainingStock(long productId) {
        Integer redisStock = stockCounter.currentStock(productId);
        if (redisStock != null) {
            if (redisStock < 0) {
                // 정상 흐름에서 음수는 발생하면 안 됨 (Lua 가 0 검사 후 DECR)
                log.error("Negative Redis stock detected (masking to 0): productId={} stock={}",
                        productId, redisStock);
            }
            return Math.max(0, redisStock);
        }
        return inventoryRepository.findById(productId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVENTORY_NOT_FOUND))
                .getRemainingStock();
    }
}
