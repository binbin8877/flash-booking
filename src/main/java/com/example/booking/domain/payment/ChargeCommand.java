package com.example.booking.domain.payment;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class ChargeCommand {
    Long userId;
    Long bookingId;
    int amount;
    /** 카드 토큰 등 수단별 부가 정보 */
    Map<String, String> attributes;
}
