package com.example.booking.infrastructure.redis;

/**
 * reconcile_stock.lua 스크립트 결과.
 * KEYS(hold:*) 확인 → GET(stock) → SET(stock, target) 을 원자 실행한 결과를 분류.
 */
public enum ReconcileResult {
    NO_ACTION,      // Lua return  0 — 활성 hold 있음 또는 stock == target
    RECONCILED,     // Lua return  1 — stock < target 이라 target 으로 복구
    INITIALIZED,    // Lua return -1 — stock 키 자체가 없어 target 으로 초기 SET
    OVER_CREDITED;  // Lua return -2 — stock > target (이상 상태, 알림)

    public static ReconcileResult fromCode(long code) {
        return switch ((int) code) {
            case 1 -> RECONCILED;
            case -1 -> INITIALIZED;
            case -2 -> OVER_CREDITED;
            default -> NO_ACTION;
        };
    }
}
