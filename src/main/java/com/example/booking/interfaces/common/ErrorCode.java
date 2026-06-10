package com.example.booking.interfaces.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 단일 진실원천 에러 카탈로그.
 * code 는 클라이언트/모니터링/CS 응대에 노출되는 안정적 식별자.
 * defaultMessage 는 사용자에게 노출되는 기본 한국어 메시지 — 동적 메시지가 필요한 경우 ApiException 의 override 생성자 사용.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // === Lookup (조회 실패) ===
    PRODUCT_NOT_FOUND       (HttpStatus.NOT_FOUND, "product.not_found",    "상품을 찾을 수 없습니다."),
    USER_NOT_FOUND          (HttpStatus.NOT_FOUND, "user.not_found",       "사용자를 찾을 수 없습니다."),
    INVENTORY_NOT_FOUND     (HttpStatus.NOT_FOUND, "inventory.not_found",  "재고 정보를 찾을 수 없습니다."),

    // === Stock (재고) ===
    STOCK_SOLD_OUT          (HttpStatus.GONE,      "stock.sold_out",       "매진되었습니다."),
    STOCK_DUPLICATE_HOLD    (HttpStatus.CONFLICT,  "stock.duplicate_hold", "같은 사용자의 진행 중인 결제가 있습니다."),

    // === Payment composition (결제 조합 검증) ===
    PAYMENT_LINES_EMPTY     (HttpStatus.BAD_REQUEST, "payment.lines.empty",     "결제 라인이 비어 있습니다."),
    PAYMENT_LINES_TOO_MANY  (HttpStatus.BAD_REQUEST, "payment.lines.too_many",  "결제 라인은 최대 2개까지 허용됩니다."),
    PAYMENT_AMOUNT_INVALID  (HttpStatus.BAD_REQUEST, "payment.amount.invalid",  "결제 금액은 0보다 커야 합니다."),
    PAYMENT_METHOD_UNKNOWN  (HttpStatus.BAD_REQUEST, "payment.method.unknown",  "지원하지 않는 결제 수단입니다."),
    PAYMENT_MAIN_CONFLICT   (HttpStatus.BAD_REQUEST, "payment.main.conflict",   "신용카드와 Y페이는 동시에 사용할 수 없습니다."),
    PAYMENT_SUB_TOO_MANY    (HttpStatus.BAD_REQUEST, "payment.sub.too_many",    "포인트 라인은 최대 1개만 가능합니다."),
    PAYMENT_AMOUNT_MISMATCH (HttpStatus.BAD_REQUEST, "payment.amount.mismatch", "결제 금액 합계가 주문 금액과 일치하지 않습니다."),

    // === Payment execution (결제 실행 실패) ===
    PAYMENT_FAILED          (HttpStatus.PAYMENT_REQUIRED,    "payment.failed",   "결제가 실패했습니다."),
    PG_UNAVAILABLE          (HttpStatus.SERVICE_UNAVAILABLE, "pg.unavailable",   "결제 시스템이 일시적으로 사용할 수 없습니다."),

    // === Idempotency ===
    IDEMPOTENCY_KEY_INVALID (HttpStatus.BAD_REQUEST,         "idempotency.key.invalid", "유효하지 않은 멱등 키입니다."),
    IDEMPOTENCY_IN_FLIGHT   (HttpStatus.CONFLICT,            "idempotency.in_flight",   "동일한 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요."),

    // === Rate limiting ===
    RATE_LIMITED            (HttpStatus.TOO_MANY_REQUESTS,   "rate.limited",            "요청량이 일시적으로 한도를 초과했습니다. 잠시 후 다시 시도해 주세요."),

    // === Concurrency (낙관적 락 충돌) ===
    CONCURRENT_MODIFICATION (HttpStatus.CONFLICT,            "concurrent.modification", "동시 결제 충돌이 발생했습니다. 잠시 후 다시 시도해 주세요."),

    // === Internal (영속성/일관성 깨짐 — 발생하면 버그) ===
    BOOKING_MISSING         (HttpStatus.INTERNAL_SERVER_ERROR, "booking.missing",   "예약 정보를 찾을 수 없습니다."),
    PAYMENT_MISSING         (HttpStatus.INTERNAL_SERVER_ERROR, "payment.missing",   "결제 정보를 찾을 수 없습니다."),
    INVENTORY_MISSING       (HttpStatus.INTERNAL_SERVER_ERROR, "inventory.missing", "재고 정보를 찾을 수 없습니다."),

    // === Fallback (어떤 카테고리에도 매핑 안 된 경우) ===
    INTERNAL_ERROR          (HttpStatus.INTERNAL_SERVER_ERROR, "internal.error",  "내부 오류"),
    REQUEST_INVALID         (HttpStatus.BAD_REQUEST,           "request.invalid", "잘못된 요청"),
    HEADER_MISSING          (HttpStatus.BAD_REQUEST,           "header.missing",  "필수 헤더 누락");

    private final HttpStatus status;
    private final String code;
    private final String defaultMessage;
}
