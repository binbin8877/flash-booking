package com.example.booking.infrastructure.redis;

import com.example.booking.domain.product.ProductInventory;
import com.example.booking.infrastructure.persistence.ProductInventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 앱 기동 시 DB의 잔여 재고를 Redis 카운터로 동기화한다.
 * 동시에 여러 노드가 기동되어도 SETNX 의미는 없고 마지막 값이 승.
 * 운영에서는 별도 관리 도구로 초기화하는 것이 안전하지만, 데모 편의상 자동 부트스트랩.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisStockBootstrap {

    private final ProductInventoryRepository inventoryRepository;
    private final StockCounter stockCounter;

    @EventListener(ApplicationReadyEvent.class)
    public void syncStockToRedis() {
        for (ProductInventory inventory : inventoryRepository.findAll()) {
            stockCounter.initialize(inventory.getProductId(), inventory.getRemainingStock());
            log.info("Redis stock initialized: productId={} stock={}", inventory.getProductId(), inventory.getRemainingStock());
        }
    }
}
