package com.example.booking.domain.payment;

public enum PaymentMethodType {
    CREDIT_CARD(MethodKind.MAIN),
    Y_PAY(MethodKind.MAIN),
    Y_POINT(MethodKind.SUB);

    private final MethodKind kind;

    PaymentMethodType(MethodKind kind) {
        this.kind = kind;
    }

    public MethodKind kind() {
        return kind;
    }
}
