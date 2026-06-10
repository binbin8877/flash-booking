package com.example.booking.domain.payment.strategy;

import com.example.booking.domain.payment.ChargeCommand;
import com.example.booking.domain.payment.PaymentMethod;
import com.example.booking.domain.payment.PaymentMethodType;
import com.example.booking.domain.payment.PaymentResult;
import com.example.booking.infrastructure.pg.PaymentGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CreditCardPayment implements PaymentMethod {

    private final PaymentGateway paymentGateway;

    @Override
    public PaymentMethodType type() {
        return PaymentMethodType.CREDIT_CARD;
    }

    @Override
    public PaymentResult charge(ChargeCommand command) {
        String cardToken = command.getAttributes() != null ? command.getAttributes().get("cardToken") : null;
        if (cardToken == null || cardToken.isBlank()) {
            return PaymentResult.failure("missing_card_token");
        }
        var resp = paymentGateway.charge(new PaymentGateway.PgChargeRequest(
                command.getUserId(),
                command.getBookingId(),
                command.getAmount(),
                "CREDIT_CARD",
                cardToken
        ));
        return resp.success()
                ? PaymentResult.success(resp.externalRef())
                : PaymentResult.failure(resp.failureReason());
    }

    @Override
    public void cancel(String externalRef) {
        paymentGateway.cancel(externalRef);
    }
}
