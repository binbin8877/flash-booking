package com.example.booking.interfaces.common;

import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class ErrorAdvice {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus())
                .body(new ErrorResponse(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<ErrorResponse> handleRateLimited(RequestNotPermitted e) {
        return ResponseEntity.status(ErrorCode.RATE_LIMITED.getStatus())
                .header("Retry-After", "1")
                .body(new ErrorResponse(ErrorCode.RATE_LIMITED.getCode(),
                        ErrorCode.RATE_LIMITED.getDefaultMessage()));
    }

    /**
     * 낙관적 락 충돌 (User.point_balance 등) — 같은 사용자가 동시 결제 시 발생 가능.
     * 시스템 정합성은 유지되며 (rollback + sweeper refund), 사용자는 재시도하면 성공한다.
     * 진짜 장애와 분리해 INFO 레벨로 기록 + 명확한 409 안내.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException e) {
        log.info("optimistic lock conflict (user retry expected): {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.CONCURRENT_MODIFICATION.getStatus())
                .body(new ErrorResponse(ErrorCode.CONCURRENT_MODIFICATION.getCode(),
                        ErrorCode.CONCURRENT_MODIFICATION.getDefaultMessage()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.status(ErrorCode.HEADER_MISSING.getStatus())
                .body(new ErrorResponse(ErrorCode.HEADER_MISSING.getCode(),
                        ErrorCode.HEADER_MISSING.getDefaultMessage() + ": " + e.getHeaderName()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse(ErrorCode.REQUEST_INVALID.getDefaultMessage());
        return ResponseEntity.status(ErrorCode.REQUEST_INVALID.getStatus())
                .body(new ErrorResponse(ErrorCode.REQUEST_INVALID.getCode(), msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleEtc(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(new ErrorResponse(ErrorCode.INTERNAL_ERROR.getCode(),
                        ErrorCode.INTERNAL_ERROR.getDefaultMessage()));
    }
}
