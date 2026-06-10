package com.example.booking.application.booking;

import com.example.booking.application.payment.PaymentCompositionValidator;
import com.example.booking.application.payment.PaymentFailedException;
import com.example.booking.application.payment.PgCallLogger;
import com.example.booking.domain.product.Product;
import com.example.booking.infrastructure.persistence.ProductRepository;
import com.example.booking.infrastructure.pg.PgUnavailableException;
import com.example.booking.infrastructure.redis.RedisUnavailableException;
import com.example.booking.infrastructure.redis.ReserveResult;
import com.example.booking.infrastructure.redis.StockCounter;
import com.example.booking.interfaces.common.ApiException;
import com.example.booking.interfaces.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 예약 처리 흐름을 조율. Redis 가 살아있으면 Lua 경로, 죽었으면 DB 경로로 분기하고,
 * 실패 시 잡아둔 Redis stock 을 되돌리는 보상 호출도 여기서 함.
 * 실제 DB INSERT/UPDATE 는 BookingPersistence 가 처리.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final ProductRepository productRepository;
    private final StockCounter stockCounter;
    private final PaymentCompositionValidator compositionValidator;
    private final BookingPersistence bookingPersistence;
    private final PgCallLogger pgCallLogger;

    public BookingResult book(BookingCommand command) {
        Product product = productRepository.findById(command.getProductId())
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND));

        compositionValidator.validate(product.getPrice(), command.getPaymentLines());

        // 두 경로:
        //   1) Redis 정상 → Lua 원자 차감 → BookingPersistence.persist (DB advisory lock 불요)
        //   2) Redis 다운 → BookingPersistence.persistWithDbAdvisoryLock (MySQL GET_LOCK + FOR UPDATE)
        boolean usedRedisPath = false;
        try {
            ReserveResult reserveResult = stockCounter.reserve(
                    command.getProductId(), command.getUserId());
            switch (reserveResult) {
                case SOLD_OUT -> throw new ApiException(ErrorCode.STOCK_SOLD_OUT);
                case DUPLICATE_HOLD -> throw new ApiException(ErrorCode.STOCK_DUPLICATE_HOLD);
                case STOCK_KEY_MISSING -> {
                    // Redis 는 살아 있으나 stock 키가 없음 — DB 폴백
                    log.warn("Redis stock key missing for productId={}, using DB fallback",
                            command.getProductId());
                    return runDbPath(command, product);
                }
                case RESERVED -> { usedRedisPath = true; }
            }
        } catch (RedisUnavailableException e) {
            log.warn("Redis unavailable, falling back to DB advisory lock path");
            return runDbPath(command, product);
        }

        // Redis 경로 — Lua 가 성공한 케이스
        try {
            BookingResult result = bookingPersistence.persist(command, product);
            pgCallLogger.markReconciled(result.getBookingId());
            return result;
        } catch (PaymentFailedException pfe) {
            safeReleaseRedis(command);
            throw new ApiException(ErrorCode.PAYMENT_FAILED, "결제에 실패했습니다: " + pfe.getReason());
        } catch (PgUnavailableException e) {
            safeReleaseRedis(command);
            throw new ApiException(ErrorCode.PG_UNAVAILABLE);
        } catch (DataIntegrityViolationException e) {
            safeReleaseRedis(command);
            try {
                return bookingPersistence.reconstruct(command.getIdempotencyKey());
            } catch (ApiException reconstructFail) {
                throw e;
            }
        } catch (RuntimeException e) {
            if (usedRedisPath) safeReleaseRedis(command);
            throw e;
        }
    }

    private BookingResult runDbPath(BookingCommand command, Product product) {
        try {
            BookingResult result = bookingPersistence.persistWithDbAdvisoryLock(command, product);
            pgCallLogger.markReconciled(result.getBookingId());
            return result;
        } catch (PaymentFailedException pfe) {
            throw new ApiException(ErrorCode.PAYMENT_FAILED, "결제에 실패했습니다: " + pfe.getReason());
        } catch (PgUnavailableException e) {
            throw new ApiException(ErrorCode.PG_UNAVAILABLE);
        } catch (DataIntegrityViolationException e) {
            try {
                return bookingPersistence.reconstruct(command.getIdempotencyKey());
            } catch (ApiException reconstructFail) {
                throw e;
            }
        }
        // 그 외 RuntimeException 은 그대로 전파 — DB 트랜잭션 자동 롤백 + advisory lock 은 finally 에서 해제됨.
    }

    private void safeReleaseRedis(BookingCommand command) {
        try {
            stockCounter.release(command.getProductId(), command.getUserId());
        } catch (RedisUnavailableException e) {
            log.warn("redis release skipped (down): productId={} userId={}",
                    command.getProductId(), command.getUserId());
        }
    }
}
