package com.example.booking.domain.payment.strategy;

import com.example.booking.domain.payment.ChargeCommand;
import com.example.booking.domain.payment.PaymentMethod;
import com.example.booking.domain.payment.PaymentMethodType;
import com.example.booking.domain.payment.PaymentResult;
import com.example.booking.domain.user.PointTransaction;
import com.example.booking.domain.user.User;
import com.example.booking.infrastructure.persistence.PointTransactionRepository;
import com.example.booking.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 포인트는 외부 PG가 아니라 내부 트랜잭션에서 차감.
 * 같은 booking 트랜잭션 안에서 동작하므로 charge/cancel이 DB 트랜잭션에 참여.
 */
@Component
@RequiredArgsConstructor
public class YPointPayment implements PaymentMethod {

    private final UserRepository userRepository;
    private final PointTransactionRepository pointRepository;

    @Override
    public PaymentMethodType type() {
        return PaymentMethodType.Y_POINT;
    }

    @Override
    public PaymentResult charge(ChargeCommand command) {
        User user = userRepository.findById(command.getUserId()).orElse(null);
        if (user == null) {
            return PaymentResult.failure("user_not_found");
        }
        if (user.getPointBalance() < command.getAmount()) {
            return PaymentResult.failure("insufficient_points");
        }
        user.usePoints(command.getAmount());
        userRepository.save(user);
        PointTransaction tx = PointTransaction.use(user.getId(), command.getAmount(), command.getBookingId());
        pointRepository.save(tx);
        return PaymentResult.success("pt_" + UUID.randomUUID());
    }

    @Override
    public void cancel(String externalRef) {
        // 본 메서드는 PaymentProcessor의 보상 경로에서 호출되지만,
        // 포인트는 booking 트랜잭션 자체의 롤백으로 복구되므로 별도 처리 불필요.
        // 트랜잭션 외부에서 후처리 보상이 필요하다면 ref 파싱 + refund 호출을 추가한다.
    }
}
