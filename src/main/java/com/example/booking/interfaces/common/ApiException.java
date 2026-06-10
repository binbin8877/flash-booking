package com.example.booking.interfaces.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    /**
     * 기본 사용 — ErrorCode 의 defaultMessage 를 그대로 노출.
     */
    public ApiException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.status = errorCode.getStatus();
        this.code = errorCode.getCode();
    }

    /**
     * 동적 메시지가 필요한 경우 — 예: "상품 ID 42 를 찾을 수 없습니다" 같이 변수 포함.
     */
    public ApiException(ErrorCode errorCode, String overrideMessage) {
        super(overrideMessage);
        this.status = errorCode.getStatus();
        this.code = errorCode.getCode();
    }
}
