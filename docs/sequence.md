# Sequence Diagrams

주요 시나리오의 시퀀스 다이어그램. 모든 다이어그램은 Mermaid 문법으로 작성되어 GitHub에서 그대로 렌더링된다.

- [S1. 정상 예약 플로우](#s1-정상-예약-플로우)
- [S2. 멱등 키 중복 요청](#s2-멱등-키-중복-요청)
- [S3. 결제 실패 보상](#s3-결제-실패-보상)
- [S4. Redis 장애 폴백](#s4-redis-장애-폴백)
- [S5. PG 호출 후 앱 죽음 + Outbox 환불](#s5-pg-호출-후-앱-죽음--outbox-환불)
- [S6. RateLimiter 초과 차단](#s6-ratelimiter-초과-차단)

---

## S1. 정상 예약 플로우

사용자가 주문서 진입 → 결제 버튼 → 예약 확정까지의 정상 흐름.

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant N as Nginx
    participant A as App Node
    participant R as Redis
    participant P as PG (mock)
    participant D as MySQL

    U->>N: GET /api/v1/checkout?productId=1
    N->>A: forward
    A->>D: SELECT product / users / inventory
    A->>R: GET stock:1
    A-->>U: 200 {product, user}

    Note over U: 00:00:00 오픈, 결제 클릭

    U->>N: POST /api/v1/bookings (Idempotency-Key: K1)
    N->>A: forward (@RateLimiter check passed)
    A->>R: SETNX idem:K1 "<reserved>" EX 15m
    R-->>A: OK (신규)

    A->>A: PaymentCompositionValidator.validate()

    A->>R: EVAL reserve_stock.lua KEYS=[stock:1, hold:1:42]
    R-->>A: 1 (성공: stock 10→9, hold TTL 5m)

    Note over A,D: BookingPersistence.doPersist (@Transactional timeout=3)
    A->>D: BEGIN
    A->>D: SELECT * FROM product_inventory FOR UPDATE
    A->>D: UPDATE product_inventory SET remaining_stock=9
    A->>D: INSERT booking(idempotency_key=K1, status=PENDING)

    Note over A,P: PaymentProcessor.execute (트랜잭션 안)
    Note over A,P: line1 (MAIN/CARD) — 외부 PG 호출
    A->>P: charge(line1: CARD 47000)
    P-->>A: success {externalRef: pg_tx_abc}
    A->>D: INSERT pg_call_log (REQUIRES_NEW — 별도 tx commit)
    Note over A,D: line2 (SUB/POINT) — 내부 DB 차감 (YPointPayment, PG 미경유)
    A->>D: INSERT point_transaction(delta=-3000)
    A->>D: UPDATE users SET point_balance = point_balance - 3000, version+=1

    A->>D: UPDATE booking SET status=CONFIRMED
    A->>D: COMMIT
    A->>D: markReconciled(bookingId) → pg_call_log status=RECONCILED

    A->>R: SET idem:K1 "<응답 JSON>" EX 15m
    A-->>U: 201 {bookingId, status: CONFIRMED, ...}
```

**핵심 포인트**
- Redis Lua는 "stock 확인 + DECR + hold 등록"을 단일 원자 연산으로 수행 → race condition 원천 차단.
- **PG 호출은 DB 트랜잭션 *안*에서 진행** (DECISIONS 8번 참고). Redis Lua가 99% 차단해 DB 도달 ~10건/피크 → 락 보유 200ms 영향 미미.
- `pg_call_log`는 `REQUIRES_NEW`로 PG charge 직후 *별도 트랜잭션 commit* → 메인 트랜잭션 롤백돼도 흔적 살아남음.
- `hold` 키는 commit 직후 `release_stock.lua` 로 즉시 DEL (event-driven primary). 비정상 종료 대비 TTL 300초 backstop (DECISIONS 7번 참고).

---

## S2. 멱등 키 중복 요청

사용자 더블 클릭 또는 네트워크 재시도로 동일 `Idempotency-Key`가 두 번 도착.

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant N as Nginx
    participant A1 as App Node #1
    participant A2 as App Node #2
    participant R as Redis
    participant P as PG (mock)
    participant D as MySQL

    par 첫 번째 요청 (정상)
        U->>N: POST /bookings (Idempotency-Key: K1)
        N->>A1: forward
        A1->>R: SETNX idem:K1 "<reserved>" EX 15m
        R-->>A1: OK (신규)
        A1->>R: EVAL reserve_stock.lua
        R-->>A1: 1
        A1->>P: charge(...)
        P-->>A1: success
        A1->>D: INSERT booking + payment ...
        A1->>R: SET idem:K1 "<응답 JSON>" EX 15m
        A1-->>U: 201 CONFIRMED
    and 두 번째 요청 (중복, 다른 노드)
        U->>N: POST /bookings (Idempotency-Key: K1)
        N->>A2: forward (least_conn)
        A2->>R: SETNX idem:K1 "<reserved>"
        R-->>A2: NX 실패 (이미 존재)
        A2->>R: GET idem:K1
        alt 값이 "<reserved>"
            R-->>A2: "<reserved>"
            A2-->>U: 409 처리 중, 잠시 후 재시도
        else 값이 응답 JSON
            R-->>A2: "<응답 JSON>"
            A2-->>U: 200 (캐시된 응답 그대로)
        end
    end
```

**핵심 포인트**
- 두 요청이 서로 다른 앱 노드로 라우팅되어도 Redis가 단일 진실 원천이므로 안전.
- 비즈니스 처리가 끝나기 전 동시 중복이면 409로 빠른 거절, 끝난 후면 200으로 응답 재현.
- 최후 방어선: `booking.idempotency_key` UNIQUE 제약 (Redis 누수 시).

---

## S3. 결제 실패 보상

신용카드 결제는 성공했으나 포인트 차감이 실패한 케이스. 결제 라인 보상 + 재고 복구.

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant A as App Node
    participant R as Redis
    participant P as PG (mock)

    U->>A: POST /bookings (Idempotency-Key: K2)
    A->>R: SETNX idem:K2 "<reserved>"
    R-->>A: OK

    A->>R: EVAL reserve_stock.lua
    R-->>A: 1 (stock 9→8, hold 등록)

    A->>P: charge(CARD 47000)
    P-->>A: success {externalRef: pg_tx_xyz}

    A->>P: charge(POINT 3000)
    P-->>A: FAIL (잔액 부족)

    Note over A: 보상 시작
    A->>P: cancel(pg_tx_xyz)
    P-->>A: cancelled

    A->>R: INCR stock:1  (8→9 복구)
    A->>R: DEL hold:1:42

    A->>R: SET idem:K2 "<402 응답 JSON>" EX 15m
    A-->>U: 402 {code: "payment.failed", message: "결제에 실패했습니다: insufficient_points"}
```

**핵심 포인트**
- **순서가 중요**: 외부 시스템 보상(PG cancel) → 내부 상태 복구(stock INCR + hold DEL) → 응답.
- 보상(PG cancel) 자체가 실패하면 어떻게? → `PaymentProcessor.compensate` 가 `log.error` 만 남기고 예외는 삼킨다. PG 흔적은 `pg_call_log` (REQUIRES_NEW) 에 살아있으므로 `HoldExpirySweeper.refundOrphanedPgCharges` 가 5분마다 backstop 으로 재환불 시도.
- 멱등키에 결제 실패 응답을 캐시하는 이유 (`BookingController.shouldCacheError` 가 402/410/400 만 캐시): 동일 키로 재시도해도 같은 결과를 받게 해 *PG 중복 charge* 방지.

---

## S4. Redis 장애 폴백

Redis 가 다운된 상황에서 회로 차단기가 OPEN 되고 **MySQL `GET_LOCK` 기반 advisory lock + `product_inventory FOR UPDATE`** 경로로 폴백.

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant A as App Node
    participant CB as CircuitBreaker(redis)
    participant R as Redis (DOWN)
    participant D as MySQL

    U->>A: POST /bookings (Idempotency-Key: K3)

    Note over A: IdempotencyStore.tryAcquire
    A->>CB: SETNX idem:K3
    CB->>R: ...
    R--xCB: timeout / connection refused
    Note over CB: 실패 누적 → OPEN
    CB-->>A: RedisUnavailableException
    Note over A: fallback — 통과 (DB UNIQUE 가 최후 멱등 방어선)

    A->>CB: stockCounter.reserve
    CB-->>A: RedisUnavailableException (회로 OPEN, fast-fail)
    Note over A: BookingService.runDbPath

    A->>D: BEGIN tx
    A->>D: SELECT GET_LOCK('booking:p1:u42', 0)
    D-->>A: 1 (acquired)
    A->>D: SELECT * FROM product_inventory WHERE product_id=1 FOR UPDATE
    D-->>A: remaining_stock=8
    alt stock > 0
        A->>D: UPDATE product_inventory SET remaining_stock=7
        A->>D: INSERT booking (UNIQUE idempotency_key 체크)
        alt 동일 키 이미 처리됨
            D-->>A: DuplicateKeyException
            A->>D: SELECT RELEASE_LOCK(...)
            A->>D: ROLLBACK
            A-->>U: 200 (기존 응답 reconstruct)
        else 신규 키
            A->>D: INSERT payment, payment_line
            A->>A: PaymentProcessor.execute (mock PG)
            A->>D: SELECT RELEASE_LOCK('booking:p1:u42')
            A->>D: COMMIT
            A-->>U: 201 CONFIRMED
        end
    else stock = 0
        A->>D: SELECT RELEASE_LOCK(...)
        A->>D: ROLLBACK
        A-->>U: 410 매진
    end

    Note over CB: 일정 시간 후 HALF_OPEN → 복구 후 CLOSE
```

**핵심 포인트**
- 폴백은 두 경계에서 발생:
  1. **멱등 키 체크 단계** (`IdempotencyStore`) — Redis 다운 시 fallback 으로 *통과* → `booking.idempotency_key` UNIQUE 제약이 최후 방어선 (동일 키 중복 INSERT 거부 + `reconstruct` 로 같은 응답 재구성).
  2. **재고 reserve 단계** (`StockCounter.reserve`) — DB advisory lock + `FOR UPDATE` 로 폴백.
- `MySQL GET_LOCK('booking:p{}:u{}', 0)` — timeout=0 (NOWAIT) 으로 즉시 결과 반환. 다른 사용자끼리는 락 충돌 없음.
- `product_inventory FOR UPDATE` 가 모든 동시 요청을 직렬화하여 매진 정합성 보장.
- `finally { RELEASE_LOCK(...) }` 로 connection 풀 반환 시 lock 잔존 방지.
- 처리량은 ~50 TPS 수준으로 떨어지지만, 매진 임박 시점이라 사용자 영향 제한 (DECISIONS 4번 참고).

---

## S5. PG 호출 후 앱 죽음 + Outbox 환불

PG charge 직후 메인 트랜잭션 commit 전에 앱이 죽는 경우. **돈 손실 없음** 을 보장하는 Outbox 패턴.

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant A as App Node
    participant R as Redis
    participant P as PG (mock)
    participant D as MySQL
    participant S as HoldExpirySweeper

    U->>A: POST /bookings (Idempotency-Key: K4)
    A->>R: SETNX idem:K4 "<reserved>"
    A->>R: EVAL reserve_stock.lua → 1

    A->>D: BEGIN @Transactional(timeout=3)
    A->>D: SELECT inv FOR UPDATE → INSERT booking(PENDING)

    A->>P: charge(CARD 50000)
    P-->>A: success {externalRef: pg_xyz}

    Note over A,D: PgCallLogger.recordSuccess (REQUIRES_NEW)
    A->>D: BEGIN (별도 tx)
    A->>D: INSERT pg_call_log(status=PG_CHARGED)
    A->>D: COMMIT ✅ (메인 tx와 무관)

    Note over A: 앱 노드 죽음 (JVM crash, kill -9 등)
    Note over D: 메인 트랜잭션 ROLLBACK — booking/payment 모두 없음. pg_call_log 만 살아남음

    Note over S: 5분마다 sweeper 실행 (cron 0 */5 * * * *)

    S->>D: SELECT * FROM pg_call_log WHERE status='PG_CHARGED' AND created_at < NOW()-5m FOR UPDATE SKIP LOCKED
    D-->>S: [pg_call_log row]

    S->>D: SELECT booking WHERE id=?
    D-->>S: (없음 — 트랜잭션 롤백됨)

    Note over S,P: 고아 PG charge 발견 → 환불
    S->>P: cancel(pg_xyz)
    P-->>S: cancelled
    S->>D: UPDATE pg_call_log SET status=REFUNDED
```

**핵심 포인트**
- `pg_call_log` 의 `REQUIRES_NEW` 가 핵심 — 메인 트랜잭션과 *독립 commit* → 어떤 실패에도 흔적 남음.
- `SELECT ... FOR UPDATE SKIP LOCKED` (MySQL 8.0+) 가 멀티 노드 sweeper 중복 처리 차단.
- Redis hold 키는 TTL (5분) 으로 자동 만료 → 다른 사용자가 그 자리 잡을 수 있음.
- 사용자: 응답 못 받음 → 멱등키로 재시도하면 신규 처리 (기존 흔적 환불됨).
- **PG charge 후 `pg_call_log` INSERT 사이 (~0.1ms 윈도우)** 만 *유일한 잔여 위험* — 매우 좁음.

---

## S6. RateLimiter 초과 차단

자정 폭주 시 시스템 1000 TPS 한도 초과 → 입구에서 차단.

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant N as Nginx
    participant A as App Node
    participant RL as @RateLimiter(booking)
    participant R as Redis
    participant D as MySQL

    Note over U,A: 자정 정각, 1초간 1500 요청 도착

    par 요청 500개 (한도 내)
        U->>N: POST /bookings (...)
        N->>A: forward
        A->>RL: tryAcquirePermission
        RL-->>A: ✅ 허용
        A->>R: 정상 처리 (idempotency + Lua)
        A->>D: 정상 트랜잭션
        A-->>U: 201 CREATED 또는 410 SOLD_OUT
    and 요청 1000개 (한도 초과)
        U->>N: POST /bookings (...)
        N->>A: forward
        A->>RL: tryAcquirePermission
        RL-->>A: ❌ RequestNotPermitted
        Note over A: ErrorAdvice.handleRateLimited
        A-->>U: 429 {code: rate.limited, Retry-After: 1}
    end

    Note over U: 클라이언트 자동 재시도 (1초 후) 또는 사용자 새로고침
```

**핵심 포인트**
- `@RateLimiter(name="booking")` 는 **노드당** 500/sec → 2 노드 시스템 합계 **1000/sec** (피크 수용 한도).
- 큐 대기 0초 (`timeoutDuration: 0`) → 초과 즉시 거절 = cascade 차단.
- *정상 시나리오* (Redis Lua 가 99% 차단) 엔 RateLimiter 거의 발동 안 함.
- *비정상 시나리오* (Redis 다운 + 1000 TPS) 에 *DB 보호 핵심 안전망* (DECISIONS 1번 참고).

---
