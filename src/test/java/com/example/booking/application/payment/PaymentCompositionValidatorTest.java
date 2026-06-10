package com.example.booking.application.payment;

import com.example.booking.domain.payment.ChargeCommand;
import com.example.booking.domain.payment.PaymentMethod;
import com.example.booking.domain.payment.PaymentMethodType;
import com.example.booking.domain.payment.PaymentResult;
import com.example.booking.interfaces.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentCompositionValidatorTest {

    private PaymentCompositionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PaymentCompositionValidator(List.of(
                stub(PaymentMethodType.CREDIT_CARD),
                stub(PaymentMethodType.Y_PAY),
                stub(PaymentMethodType.Y_POINT)
        ));
    }

    @Test
    void 신용카드_단독_허용() {
        validator.validate(50_000, List.of(line(PaymentMethodType.CREDIT_CARD, 50_000)));
    }

    @Test
    void Y페이_단독_허용() {
        validator.validate(50_000, List.of(line(PaymentMethodType.Y_PAY, 50_000)));
    }

    @Test
    void 포인트_단독_허용() {
        validator.validate(3_000, List.of(line(PaymentMethodType.Y_POINT, 3_000)));
    }

    @Test
    void 신용카드_포인트_복합_허용() {
        validator.validate(50_000, List.of(
                line(PaymentMethodType.CREDIT_CARD, 47_000),
                line(PaymentMethodType.Y_POINT, 3_000)
        ));
    }

    @Test
    void Y페이_포인트_복합_허용() {
        validator.validate(50_000, List.of(
                line(PaymentMethodType.Y_PAY, 47_000),
                line(PaymentMethodType.Y_POINT, 3_000)
        ));
    }

    @Test
    void 신용카드_Y페이_혼용_거절() {
        assertThatThrownBy(() -> validator.validate(50_000, List.of(
                line(PaymentMethodType.CREDIT_CARD, 30_000),
                line(PaymentMethodType.Y_PAY, 20_000)
        )))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void 포인트_두_라인_거절() {
        assertThatThrownBy(() -> validator.validate(50_000, List.of(
                line(PaymentMethodType.Y_POINT, 25_000),
                line(PaymentMethodType.Y_POINT, 25_000)
        )))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 합계_불일치_거절() {
        assertThatThrownBy(() -> validator.validate(50_000, List.of(
                line(PaymentMethodType.CREDIT_CARD, 49_000)
        )))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("payment.amount.mismatch"));
    }

    @Test
    void 빈_라인_거절() {
        assertThatThrownBy(() -> validator.validate(50_000, List.of()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 세_라인_이상_거절() {
        assertThatThrownBy(() -> validator.validate(50_000, List.of(
                line(PaymentMethodType.CREDIT_CARD, 20_000),
                line(PaymentMethodType.Y_PAY, 20_000),
                line(PaymentMethodType.Y_POINT, 10_000)
        )))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 금액_0이하_거절() {
        assertThatThrownBy(() -> validator.validate(50_000, List.of(
                line(PaymentMethodType.CREDIT_CARD, 0)
        )))
                .isInstanceOf(ApiException.class);
    }

    private PaymentCommand.Line line(PaymentMethodType type, int amount) {
        return PaymentCommand.Line.builder().method(type).amount(amount).build();
    }

    private PaymentMethod stub(PaymentMethodType type) {
        return new PaymentMethod() {
            @Override public PaymentMethodType type() { return type; }
            @Override public PaymentResult charge(ChargeCommand command) {
                return PaymentResult.success("stub");
            }
            @Override public void cancel(String externalRef) {}
        };
    }
}
