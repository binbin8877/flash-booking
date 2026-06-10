package com.example.booking.application.booking;

import com.example.booking.application.payment.PaymentCommand;
import com.example.booking.application.payment.PaymentProcessor;
import com.example.booking.domain.booking.Booking;
import com.example.booking.domain.booking.BookingStatus;
import com.example.booking.domain.payment.Payment;
import com.example.booking.domain.payment.PaymentLine;
import com.example.booking.domain.product.Product;
import com.example.booking.domain.product.ProductInventory;
import com.example.booking.infrastructure.persistence.BookingRepository;
import com.example.booking.infrastructure.persistence.DbStockFallback;
import com.example.booking.infrastructure.persistence.PaymentLineRepository;
import com.example.booking.infrastructure.persistence.PaymentRepository;
import com.example.booking.infrastructure.persistence.ProductInventoryRepository;
import com.example.booking.interfaces.common.ApiException;
import com.example.booking.interfaces.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * BookingService 와 분리한 트랜잭션 경계.
 * Spring AOP 프록시는 self-invocation 에 동작하지 않으므로 별도 빈으로 둔다.
 *
 * 두 가지 진입점:
 *   - {@link #persist}                 : Redis 경로. Redis Lua 가 이미 사용자 hold 를 차단했으므로 DB advisory lock 불요.
 *   - {@link #persistWithDbAdvisoryLock}: DB 폴백 경로. Redis 가 죽었으므로 동일 사용자 중복 방지를 위해 MySQL GET_LOCK 사용.
 *
 * 두 경로 모두 product_inventory FOR UPDATE 로 재고를 차감하므로
 *   - Redis 가 살아 있을 때: 핫패스는 Lua, DB 는 진실원천 유지
 *   - Redis 가 죽었을 때: DB 만 사용해도 정합성 유지
 */
@Service
@RequiredArgsConstructor
public class BookingPersistence {

    private final ProductInventoryRepository inventoryRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentLineRepository paymentLineRepository;
    private final PaymentProcessor paymentProcessor;
    private final DbStockFallback dbStockFallback;

    @Transactional(timeout = 3)
    public BookingResult persist(BookingCommand command, Product product) {
        return doPersist(command, product);
    }

    /**
     * DB advisory lock 으로 사용자 중복 차단 → 트랜잭션 내부에서 lock 획득/해제.
     * Spring 의 DataSourceUtils 가 동일 트랜잭션의 connection 을 재사용하므로
     * GET_LOCK 과 이후 SQL 들이 같은 MySQL 세션에서 실행되어 lock 이 일관되게 적용된다.
     */
    @Transactional(timeout = 3)
    public BookingResult persistWithDbAdvisoryLock(BookingCommand command, Product product) {
        boolean locked = dbStockFallback.tryLock(command.getProductId(), command.getUserId());
        if (!locked) {
            throw new ApiException(ErrorCode.STOCK_DUPLICATE_HOLD);
        }
        try {
            return doPersist(command, product);
        } finally {
            // 커밋 전에 명시적으로 해제 — connection 이 풀로 반환된 후에도 lock 이 남지 않도록.
            dbStockFallback.releaseLock(command.getProductId(), command.getUserId());
        }
    }

    @Transactional(readOnly = true)
    public BookingResult reconstruct(String idempotencyKey) {
        Booking booking = bookingRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new ApiException(ErrorCode.BOOKING_MISSING));
        Payment payment = paymentRepository.findByBookingId(booking.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_MISSING));
        List<PaymentLine> lines = paymentLineRepository.findByPaymentId(payment.getId());
        return toResult(booking, payment, lines, true);
    }

    private BookingResult doPersist(BookingCommand command, Product product) {
        ProductInventory inv = inventoryRepository.findForUpdate(product.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.INVENTORY_MISSING));
        if (inv.getRemainingStock() <= 0) {
            throw new ApiException(ErrorCode.STOCK_SOLD_OUT);
        }
        inv.decrement();
        inventoryRepository.save(inv);

        Booking booking = bookingRepository.save(Booking.builder()
                .idempotencyKey(command.getIdempotencyKey())
                .userId(command.getUserId())
                .productId(command.getProductId())
                .totalAmount(product.getPrice())
                .status(BookingStatus.PENDING)
                .build());

        PaymentCommand paymentCommand = PaymentCommand.builder()
                .userId(command.getUserId())
                .productId(command.getProductId())
                .bookingId(booking.getId())
                .totalAmount(product.getPrice())
                .lines(command.getPaymentLines())
                .build();

        PaymentProcessor.PaymentExecution exec = paymentProcessor.execute(paymentCommand);
        booking.confirm();
        return toResult(booking, exec.payment(), exec.lines(), false);
    }

    private BookingResult toResult(Booking booking, Payment payment, List<PaymentLine> lines, boolean reconstructed) {
        return BookingResult.builder()
                .bookingId(booking.getId())
                .status(booking.getStatus())
                .confirmedAt(booking.getConfirmedAt())
                .totalAmount(payment.getTotalAmount())
                .lines(lines.stream()
                        .map(l -> BookingResult.Line.builder()
                                .method(l.getMethod().name())
                                .amount(l.getAmount())
                                .externalRef(l.getExternalRef())
                                .build())
                        .toList())
                .reconstructed(reconstructed)
                .build();
    }
}
