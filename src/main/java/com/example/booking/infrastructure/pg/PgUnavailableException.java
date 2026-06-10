package com.example.booking.infrastructure.pg;

/**
 * PG 호출이 회로 차단/벌크헤드 거절/타임아웃 등으로 실패했을 때 던지는 인프라 예외.
 * "카드 한도 초과" 같은 비즈니스 실패와 분리한다.
 */
public class PgUnavailableException extends RuntimeException {
    public PgUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
