package com.example.booking.infrastructure.scheduling;

import com.example.booking.domain.booking.Booking;
import com.example.booking.domain.booking.BookingStatus;
import com.example.booking.domain.payment.PgCallLog;
import com.example.booking.domain.product.ProductInventory;
import com.example.booking.infrastructure.persistence.BookingRepository;
import com.example.booking.infrastructure.persistence.PgCallLogRepository;
import com.example.booking.infrastructure.persistence.ProductInventoryRepository;
import com.example.booking.config.HoldProperties;
import com.example.booking.config.SweeperProperties;
import com.example.booking.infrastructure.pg.PaymentGateway;
import com.example.booking.infrastructure.redis.ReconcileResult;
import com.example.booking.infrastructure.redis.StockCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 두 가지 보상 잡:
 *
 *  1. PG 환불 (refundOrphanedPgCharges):
 *     booking 트랜잭션 commit 전에 PG.charge() 가 성공했지만 외부 트랜잭션이 롤백됐거나
 *     reconcile 마킹 전에 앱이 죽은 경우, pg_call_log 에 PG_CHARGED 행이 남는다.
 *     본 잡은 holdTtl 보다 오래된 PG_CHARGED 행을 SKIP LOCKED 로 가져와 PG.cancel 호출.
 *
 *  2. 재고 reconcile (reconcileStock):
 *     Redis hold 키가 TTL 만료로 사라졌으나 booking 이 commit 되지 않은 경우
 *     Redis stock 카운터가 영구적으로 1 적게 남는다.
 *     본 잡은 product 별로 활성 hold 가 없을 때 Redis stock 을 DB inventory 와 동기화.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HoldExpirySweeper {

    private final BookingRepository bookingRepository;
    private final PgCallLogRepository pgCallLogRepository;
    private final ProductInventoryRepository inventoryRepository;
    private final StockCounter stockCounter;
    private final PaymentGateway paymentGateway;
    private final HoldProperties holdProps;
    private final SweeperProperties sweeperProps;

    @Scheduled(cron = "${app.hold.sweep-cron:0 * * * * *}")
    public void sweep() {
        try {
            int refunded = refundOrphanedPgCharges();
            int reconciled = reconcileStock();
            if (refunded > 0 || reconciled > 0) {
                log.info("sweeper run: refunded={} reconciledProducts={}", refunded, reconciled);
            }
        } catch (RuntimeException e) {
            log.error("sweeper failed: {}", e.toString());
        }
    }

    /**
     * pg_call_log.status = PG_CHARGED 인데 booking 이 CONFIRMED 가 아닌 행을 찾아 PG.cancel.
     * SKIP LOCKED 로 멀티 노드 sweeper 중복 처리 방지.
     */
    @Transactional
    public int refundOrphanedPgCharges() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(holdProps.ttlSeconds());
        List<PgCallLog> candidates = pgCallLogRepository.findExpiredForRefund(threshold, sweeperProps.batchSize());

        int processed = 0;
        for (PgCallLog entry : candidates) {
            Optional<Booking> bookingOpt = bookingRepository.findById(entry.getBookingId());
            if (bookingOpt.isPresent() && bookingOpt.get().getStatus() == BookingStatus.CONFIRMED) {
                // booking 은 정상 commit 됐는데 reconcile 마킹만 누락된 경우 — cancel 호출 금지
                entry.markReconciled();
                continue;
            }

            try {
                paymentGateway.cancel(entry.getExternalRef());
                entry.markRefunded();
                processed++;
                log.warn("orphaned PG charge refunded: bookingId={} externalRef={}",
                        entry.getBookingId(), entry.getExternalRef());

                // 환불 직후 hold 키 해제 + Redis 재고 즉시 복구 (이벤트 기반)
                // V4 이전 행은 productId/userId 가 null — 그 경우 polling reconcile 이 backstop
                if (entry.getProductId() != null && entry.getUserId() != null) {
                    boolean released = stockCounter.release(entry.getProductId(), entry.getUserId());
                    if (released) {
                        log.info("hold released immediately after refund: productId={} userId={}",
                                entry.getProductId(), entry.getUserId());
                    }
                }
            } catch (RuntimeException e) {
                entry.markRefundFailed();
                log.error("PG refund failed: bookingId={} externalRef={} cause={}",
                        entry.getBookingId(), entry.getExternalRef(), e.toString());
            }
        }
        return processed;
    }

    /**
     * 활성 hold 가 없는 상품에 한해 Redis stock 을 DB inventory 로 동기화.
     * hold 검사 + stock 비교 + 덮어쓰기를 단일 Lua 스크립트로 원자 실행한다 —
     * 자바에서 3 명령으로 나누면 사이에 reserve Lua 가 끼어드는 TOCTOU race 가 발생할 수 있다.
     */
    public int reconcileStock() {
        int touched = 0;
        for (ProductInventory inv : inventoryRepository.findAll()) {
            long productId = inv.getProductId();
            ReconcileResult result = stockCounter.reconcileIfIdle(productId, inv.getRemainingStock());
            switch (result) {
                case RECONCILED -> {
                    touched++;
                    log.warn("Redis stock drift recovered: productId={} target={}",
                            productId, inv.getRemainingStock());
                }
                case INITIALIZED -> {
                    touched++;
                    log.info("Redis stock initialized (missing key): productId={} target={}",
                            productId, inv.getRemainingStock());
                }
                case OVER_CREDITED -> log.error(
                        "Redis stock > DB inventory (over-credited!): productId={} db={}",
                        productId, inv.getRemainingStock());
                case NO_ACTION -> { /* 활성 hold 있음 또는 stock == target — 정상 */ }
            }
        }
        return touched;
    }
}
