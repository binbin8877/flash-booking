package com.example.booking.infrastructure.pg;

public interface PaymentGateway {

    PgChargeResponse charge(PgChargeRequest request);

    void cancel(String externalRef);

    record PgChargeRequest(Long userId, Long bookingId, int amount, String method, String cardToken) {}

    record PgChargeResponse(boolean success, String externalRef, String failureReason) {}
}
