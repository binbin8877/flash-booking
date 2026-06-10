package com.example.booking.infrastructure.redis;

/**
 * Redis Lua 스크립트의 재고 예약 결과.
 * Lua 가 반환하는 정수 코드를 의미 있는 타입으로 변환한다.
 */
public enum ReserveResult {
    RESERVED,           // Lua return 1  — 예약 성공
    SOLD_OUT,           // Lua return 0  — 매진
    DUPLICATE_HOLD,     // Lua return -1 — 같은 사용자 진행 중
    STOCK_KEY_MISSING;  // Lua return -2 — Redis 키 없음, DB 폴백 필요

    public static ReserveResult fromCode(long code) {
        return switch ((int) code) {
            case 1 -> RESERVED;
            case 0 -> SOLD_OUT;
            case -1 -> DUPLICATE_HOLD;
            default -> STOCK_KEY_MISSING;
        };
    }
}
