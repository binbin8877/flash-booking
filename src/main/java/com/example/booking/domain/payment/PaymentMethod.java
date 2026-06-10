package com.example.booking.domain.payment;

/**
 * 결제 수단 전략. 새 수단 추가 = 본 인터페이스 구현체를 Spring Bean으로 등록.
 * Booking API/Service 코드는 변경 없음 (OCP).
 */
public interface PaymentMethod {

    PaymentMethodType type();

    default MethodKind kind() {
        return type().kind();
    }

    /**
     * 결제 라인 1건을 청구한다. 실패 시 PaymentException 또는 결과의 success=false.
     */
    PaymentResult charge(ChargeCommand command);

    /**
     * 보상(취소) 호출. 멱등하게 동작해야 한다.
     */
    void cancel(String externalRef);
}
