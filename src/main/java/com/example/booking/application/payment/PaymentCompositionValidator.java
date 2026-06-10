package com.example.booking.application.payment;

import com.example.booking.domain.payment.MethodKind;
import com.example.booking.domain.payment.PaymentMethod;
import com.example.booking.domain.payment.PaymentMethodType;
import com.example.booking.interfaces.common.ApiException;
import com.example.booking.interfaces.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class PaymentCompositionValidator {

    /** 결제 라인 최대 수. CheckoutService 가 이 상수를 그대로 응답에 노출 — 규칙 단일 소스. */
    public static final int MAX_LINES = 2;
    /** MAIN(카드/Y페이) 결제수단 최대 수. 1 = 동시 사용 불가. */
    public static final int MAX_MAIN  = 1;
    /** SUB(포인트) 결제수단 최대 수. */
    public static final int MAX_SUB   = 1;

    private final Map<PaymentMethodType, PaymentMethod> methodByType;

    public PaymentCompositionValidator(List<PaymentMethod> methods) {
        EnumMap<PaymentMethodType, PaymentMethod> map = new EnumMap<>(PaymentMethodType.class);
        for (PaymentMethod m : methods) {
            map.put(m.type(), m);
        }
        this.methodByType = map;
    }

    public void validate(int expectedTotal, List<PaymentCommand.Line> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new ApiException(ErrorCode.PAYMENT_LINES_EMPTY);
        }
        if (lines.size() > MAX_LINES) {
            throw new ApiException(ErrorCode.PAYMENT_LINES_TOO_MANY);
        }

        int mainCount = 0;
        int subCount = 0;
        int sum = 0;
        for (PaymentCommand.Line line : lines) {
            if (line.getAmount() <= 0) {
                throw new ApiException(ErrorCode.PAYMENT_AMOUNT_INVALID);
            }
            PaymentMethod method = methodByType.get(line.getMethod());
            if (method == null) {
                throw new ApiException(ErrorCode.PAYMENT_METHOD_UNKNOWN,
                        "지원하지 않는 결제 수단: " + line.getMethod());
            }
            if (method.kind() == MethodKind.MAIN) mainCount++;
            else subCount++;
            sum += line.getAmount();
        }

        if (mainCount > MAX_MAIN) {
            throw new ApiException(ErrorCode.PAYMENT_MAIN_CONFLICT);
        }
        if (subCount > MAX_SUB) {
            throw new ApiException(ErrorCode.PAYMENT_SUB_TOO_MANY);
        }
        if (sum != expectedTotal) {
            throw new ApiException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
    }
}
