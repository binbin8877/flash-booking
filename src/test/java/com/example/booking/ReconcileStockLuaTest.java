package com.example.booking;

import com.example.booking.infrastructure.redis.ReconcileResult;
import com.example.booking.infrastructure.redis.StockCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * reconcile_stock.lua 의 4 가지 분기 단위 검증.
 * Lua 자체가 KEYS(hold:*) + GET(stock) + SET(stock) 을 원자 실행하므로
 * sweeper 가 사용하는 분기 4개를 직접 호출해 결과 코드만 확인한다.
 */
class ReconcileStockLuaTest extends IntegrationTestBase {

    @Autowired private StockCounter stockCounter;
    @Autowired private StringRedisTemplate redis;

    private static final long PRODUCT_ID = 999L;  // 시드와 겹치지 않는 가상 상품
    private static final int DB_INVENTORY = 10;

    @BeforeEach
    void cleanup() {
        redis.delete("stock:" + PRODUCT_ID);
        // hold 키들도 정리
        var holdKeys = redis.keys("hold:" + PRODUCT_ID + ":*");
        if (holdKeys != null && !holdKeys.isEmpty()) {
            redis.delete(holdKeys);
        }
    }

    @Test
    void 활성_hold_있으면_NO_ACTION() {
        // 재고 drift (Redis 9 < DB 10) 이지만 사용자 진행 중
        redis.opsForValue().set("stock:" + PRODUCT_ID, "9");
        redis.opsForValue().set("hold:" + PRODUCT_ID + ":42", "1", Duration.ofMinutes(5));

        ReconcileResult result = stockCounter.reconcileIfIdle(PRODUCT_ID, DB_INVENTORY);

        assertThat(result).isEqualTo(ReconcileResult.NO_ACTION);
        // Redis stock 은 그대로 9 (덮어쓰지 않음)
        assertThat(redis.opsForValue().get("stock:" + PRODUCT_ID)).isEqualTo("9");
    }

    @Test
    void stock_키_없으면_INITIALIZED() {
        // stock 키 없음, hold 도 없음
        // → Lua 가 target 값으로 초기 SET

        ReconcileResult result = stockCounter.reconcileIfIdle(PRODUCT_ID, DB_INVENTORY);

        assertThat(result).isEqualTo(ReconcileResult.INITIALIZED);
        assertThat(redis.opsForValue().get("stock:" + PRODUCT_ID))
                .isEqualTo(String.valueOf(DB_INVENTORY));
    }

    @Test
    void stock_부족하면_RECONCILED_로_복구() {
        // hold 없음, Redis stock = 7 < DB 10 → drift 복구 대상
        redis.opsForValue().set("stock:" + PRODUCT_ID, "7");

        ReconcileResult result = stockCounter.reconcileIfIdle(PRODUCT_ID, DB_INVENTORY);

        assertThat(result).isEqualTo(ReconcileResult.RECONCILED);
        assertThat(redis.opsForValue().get("stock:" + PRODUCT_ID))
                .isEqualTo(String.valueOf(DB_INVENTORY));
    }

    @Test
    void stock_과대면_OVER_CREDITED_경고만_상태_변경_없음() {
        // hold 없음, Redis stock = 12 > DB 10 → 이상 상태
        redis.opsForValue().set("stock:" + PRODUCT_ID, "12");

        ReconcileResult result = stockCounter.reconcileIfIdle(PRODUCT_ID, DB_INVENTORY);

        assertThat(result).isEqualTo(ReconcileResult.OVER_CREDITED);
        // Redis stock 은 *그대로 12* — sweeper 가 덮어쓰지 않고 알람만 (수동 개입 대상)
        assertThat(redis.opsForValue().get("stock:" + PRODUCT_ID)).isEqualTo("12");
    }

    @Test
    void stock_과_target_같으면_NO_ACTION() {
        // 정상 상태 — 변경할 필요 없음
        redis.opsForValue().set("stock:" + PRODUCT_ID, String.valueOf(DB_INVENTORY));

        ReconcileResult result = stockCounter.reconcileIfIdle(PRODUCT_ID, DB_INVENTORY);

        assertThat(result).isEqualTo(ReconcileResult.NO_ACTION);
        assertThat(redis.opsForValue().get("stock:" + PRODUCT_ID))
                .isEqualTo(String.valueOf(DB_INVENTORY));
    }
}
