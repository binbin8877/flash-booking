package com.example.booking.interfaces.booking;

import com.example.booking.application.booking.BookingCommand;
import com.example.booking.application.booking.BookingResult;
import com.example.booking.application.booking.BookingService;
import com.example.booking.infrastructure.redis.IdempotencyStore;
import com.example.booking.interfaces.common.ApiException;
import com.example.booking.interfaces.common.ErrorCode;
import com.example.booking.interfaces.common.ErrorResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Booking", description = "예약/결제 생성. 멱등 키 필수.")
public class BookingController {

    private final BookingService bookingService;
    private final IdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper;

    @PostMapping
    @Operation(summary = "예약 생성", description = "결제 진행과 예약 확정을 함께 수행합니다.")
    @RateLimiter(name = "booking")
    public ResponseEntity<?> create(
            @RequestHeader("X-User-Id") @Positive long userId,
            @Parameter(description = "멱등 키(UUID)") @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody BookingRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_INVALID);
        }

        // 1) 신규 요청 — Redis SETNX (다운 시 IdempotencyStore fallback 으로 통과 → DB UNIQUE 가 최후 방어)
        boolean firstOwner = idempotencyStore.tryAcquire(idempotencyKey);
        if (!firstOwner) {
            Optional<String> stored = idempotencyStore.get(idempotencyKey);
            if (stored.isEmpty()) {
                // 직전 만료된 경우 — 신규 처리로 진행
                firstOwner = idempotencyStore.tryAcquire(idempotencyKey);
            } else if (idempotencyStore.isReservedMarker(stored.get())) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_IN_FLIGHT);
            } else {
                // 캐시된 응답 그대로 반환 (200)
                return ResponseEntity.ok()
                        .header("Content-Type", "application/json")
                        .body(stored.get());
            }
        }

        try {
            BookingCommand command = BookingCommand.builder()
                    .idempotencyKey(idempotencyKey)
                    .userId(userId)
                    .productId(request.productId())
                    .paymentLines(request.payment().lines().stream()
                            .map(BookingRequest.LineDto::toCommandLine)
                            .toList())
                    .build();

            BookingResult result = bookingService.book(command);
            HttpStatus status = result.isReconstructed() ? HttpStatus.OK : HttpStatus.CREATED;
            BookingResponse response = BookingResponse.from(result);
            cacheResponse(idempotencyKey, status, response);
            return ResponseEntity.status(status).body(response);
        } catch (ApiException e) {
            // 멱등 캐시에 에러 응답을 저장하는 것은 결제 실패/매진 같은 결정적 결과만.
            if (shouldCacheError(e.getStatus())) {
                cacheResponse(idempotencyKey, e.getStatus(), new ErrorResponse(e.getCode(), e.getMessage()));
            } else {
                idempotencyStore.release(idempotencyKey);
            }
            throw e;
        } catch (RuntimeException e) {
            idempotencyStore.release(idempotencyKey);
            throw e;
        }
    }

    private boolean shouldCacheError(HttpStatus status) {
        return status == HttpStatus.PAYMENT_REQUIRED   // 결제 실패
                || status == HttpStatus.GONE           // 매진
                || status == HttpStatus.BAD_REQUEST;   // 요청 규칙 위반
    }

    private void cacheResponse(String key, HttpStatus status, Object body) {
        try {
            String json = objectMapper.writeValueAsString(new CachedResponse(status.value(), body));
            idempotencyStore.storeResponse(key, json);
        } catch (JsonProcessingException e) {
            log.warn("failed to cache idempotent response: {}", e.getMessage());
            idempotencyStore.release(key);
        }
    }

    private record CachedResponse(int status, Object body) {}
}
