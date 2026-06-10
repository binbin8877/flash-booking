package com.example.booking.application.payment;

import com.example.booking.domain.payment.ChargeCommand;
import com.example.booking.domain.payment.MethodKind;
import com.example.booking.domain.payment.Payment;
import com.example.booking.domain.payment.PaymentLine;
import com.example.booking.domain.payment.PaymentMethod;
import com.example.booking.domain.payment.PaymentMethodType;
import com.example.booking.domain.payment.PaymentResult;
import com.example.booking.domain.payment.PaymentStatus;
import com.example.booking.infrastructure.persistence.PaymentLineRepository;
import com.example.booking.infrastructure.persistence.PaymentRepository;
import com.example.booking.infrastructure.pg.PgUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class PaymentProcessor {

    private final Map<PaymentMethodType, PaymentMethod> methodByType;
    private final PaymentRepository paymentRepository;
    private final PaymentLineRepository paymentLineRepository;
    private final PgCallLogger pgCallLogger;

    public PaymentProcessor(List<PaymentMethod> methods,
                            PaymentRepository paymentRepository,
                            PaymentLineRepository paymentLineRepository,
                            PgCallLogger pgCallLogger) {
        EnumMap<PaymentMethodType, PaymentMethod> map = new EnumMap<>(PaymentMethodType.class);
        for (PaymentMethod m : methods) {
            map.put(m.type(), m);
        }
        this.methodByType = map;
        this.paymentRepository = paymentRepository;
        this.paymentLineRepository = paymentLineRepository;
        this.pgCallLogger = pgCallLogger;
    }

    /**
     * 결제 처리. 한 라인이라도 실패하면 이전 라인들을 보상 취소하고 예외 발생.
     * 호출 측은 예외를 받아 재고 복구 및 트랜잭션 롤백 책임을 진다.
     *
     * 외부 PG (MAIN kind) 호출이 성공한 직후 {@link PgCallLogger#recordSuccess} 를 호출 —
     * 본 메서드의 트랜잭션이 롤백되어도 outbox 행은 살아남아 sweeper 가 추적한다.
     */
    public PaymentExecution execute(PaymentCommand command) {
        Payment payment = paymentRepository.save(Payment.builder()
                .bookingId(command.getBookingId())
                .totalAmount(command.getTotalAmount())
                .status(PaymentStatus.PENDING)
                .build());

        List<PaymentLine> persistedLines = new ArrayList<>();
        List<SucceededRef> succeededRefs = new ArrayList<>();

        for (PaymentCommand.Line line : command.getLines()) {
            PaymentLine pl = paymentLineRepository.save(PaymentLine.builder()
                    .paymentId(payment.getId())
                    .method(line.getMethod())
                    .amount(line.getAmount())
                    .status(PaymentStatus.PENDING)
                    .build());
            persistedLines.add(pl);

            PaymentMethod strategy = methodByType.get(line.getMethod());
            ChargeCommand cmd = ChargeCommand.builder()
                    .userId(command.getUserId())
                    .bookingId(command.getBookingId())
                    .amount(line.getAmount())
                    .attributes(line.getAttributes())
                    .build();

            PaymentResult result;
            try {
                result = strategy.charge(cmd);
            } catch (PgUnavailableException e) {
                pl.markFailed();
                payment.markFailed();
                compensate(succeededRefs);
                throw e;
            } catch (RuntimeException e) {
                log.warn("payment line threw: method={} amount={} msg={}",
                        line.getMethod(), line.getAmount(), e.getMessage());
                result = PaymentResult.failure("exception:" + e.getClass().getSimpleName());
            }

            if (result.isSuccess()) {
                pl.markSuccess(result.getExternalRef());
                succeededRefs.add(new SucceededRef(strategy, result.getExternalRef()));
                // 외부 PG 호출만 outbox 기록 — 포인트(SUB) 는 DB 트랜잭션으로 자동 보상
                if (strategy.kind() == MethodKind.MAIN) {
                    pgCallLogger.recordSuccess(command.getBookingId(), command.getProductId(), command.getUserId(), result.getExternalRef());
                }
            } else {
                pl.markFailed();
                payment.markFailed();
                compensate(succeededRefs);
                throw new PaymentFailedException(result.getFailureReason(), payment, persistedLines);
            }
        }

        String firstRef = succeededRefs.isEmpty() ? null : succeededRefs.get(0).externalRef;
        payment.markSuccess(firstRef);
        return new PaymentExecution(payment, persistedLines);
    }

    private void compensate(List<SucceededRef> refs) {
        for (int i = refs.size() - 1; i >= 0; i--) {
            SucceededRef ref = refs.get(i);
            try {
                ref.method.cancel(ref.externalRef);
            } catch (RuntimeException e) {
                log.error("compensation cancel failed: method={} ref={} err={}",
                        ref.method.type(), ref.externalRef, e.getMessage());
            }
        }
    }

    public record PaymentExecution(Payment payment, List<PaymentLine> lines) {}

    private record SucceededRef(PaymentMethod method, String externalRef) {}
}
