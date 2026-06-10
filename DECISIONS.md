# 설계 결정 (DECISIONS)

본 문서는 요구사항 3축 — **가용성 / 장애 대응 / 결제수단 확장** — 에 대한 설계 판단을 기록한다. 

## 목차

| 챕터 | # | 결정 |
|---|---|---|
| 1. 가용성 | 1 | [사용자 요청을 모두 받을지](#결정-1-사용자-요청을-모두-받을지) |
| 1. 가용성 | 2 | [커넥션 풀 / 트랜잭션 timeout 산정](#결정-2-커넥션-풀--트랜잭션-timeout-산정) |
| 1. 가용성 | 3 | [공평성 — Redis 정상 시 보장, 장애 시 생존 우선](#결정-3-공평성--redis-정상-시-보장-장애-시-생존-우선) |
| 2. 장애 대응 / 예외 처리 | 4 | [Redis 장애 Fallback](#결정-4-redis-장애-fallback) |
| 2. 장애 대응 / 예외 처리 | 5 | [결제 실패 대응](#결정-5-결제-실패-대응) |
| 2. 장애 대응 / 예외 처리 | 6 | [동시성 제어 — 비관 + 낙관 락 혼용](#결정-6-동시성-제어--비관--낙관-락-혼용) |
| 2. 장애 대응 / 예외 처리 | 7 | [hold TTL 과 Sweeper 보상 구조](#결정-7-hold-ttl-과-sweeper-보상-구조) |
| 2. 장애 대응 / 예외 처리 | 8 | [PG 호출과 DB 트랜잭션의 관계](#결정-8-pg-호출과-db-트랜잭션의-관계) |
| 2. 장애 대응 / 예외 처리 | 9 | [Redis 영속화 / 메모리 정책](#결정-9-redis-영속화--메모리-정책) |
| 3. 결제수단 확장 | 10 | [PaymentMethod 인터페이스 + Validator 분리](#결정-10-paymentmethod-인터페이스--validator-분리) |

---

# 챕터 1. 가용성

## 챕터 도입 — TPS 급증 시 시스템 보호 구조

평시 50 TPS, 자정 한정 재고 (10 자리) 프로모션 시작 후 1~5분간 500~1000 TPS 순간 폭주를 가정한다.

### 채택한 시스템 보호 구조

| # | 메커니즘 | 역할                                                                            |
|---|---|-------------------------------------------------------------------------------|
| 1 | Nginx `least_conn`| 두 개의 노드로 부하 분산 + 단일 노드 다운 시 자동 fail-over                                      |
| 2 | `@RateLimiter` 500/s/node | **입구 빠른 실패** — 큐 대기 0초 (`timeoutDuration: 0`), 초과 즉시 *429 + `Retry-After: 1`* |
| 3 | Redis CircuitBreaker + DB 폴백 | Redis 다운 시 자원 격리. DB 폴백 경로로 서비스 유지                                            |

**보조 안전망** — 위 3개를 통과한 트래픽이 자원을 묶지 못하게 하는 강제 상한:

| 메커니즘 | 보호 대상                                  |
|---|----------------------------------------|
| `@Transactional(timeout=3s)` on `BookingPersistence` | 'DB 락 + PG 호출' 3초 초과 시 롤백 + 503 (Service Unavailable) |
| Hikari MySQL pool 20 + `connection-timeout: 2s` | 'DB 커넥션 획득' 2초 초과 시 503 (Service Unavailable) |

---

## 결정 1. 사용자 요청을 모두 받을지

**목적**: 폭주 트래픽이 시스템을 무너뜨리지 않도록 입구 정책을 정한다.


### 선택지

| 옵션 | 트레이드오프 |
|---|---|
| A. 큐 (대기열 시스템) | 인프라 추가 |
| **B. 빠른 실패** (RateLimiter) *(채택)* | 사용자 불만 여지 |
| C. 인프라 수직/수평 확장 | 비용 ↑ + 근본 해결 X |

### [B] RateLimiter 선택 근거

한정 재고 시스템에서 990명 실패는 어떤 방식으로도 바꿀 수 없다.
그러면 *같은 결과* 를 어떻게 가장 싸게 달성하느냐의 문제로, RateLimiter 가 *시간 0, 자원 0* 으로 같은 결과를 낸다.

| 옵션 | 990명 운명 | 낭비되는 자원 |
|---|---|---|
| A. 큐 | 실패 (변함없음) | **시간** (사용자 대기) + 인프라 (큐 시스템) |
| **B. 빠른 실패** | 실패 (변함없음) | **0** |
| C. 인프라 증설 | 실패 (변함없음) | **돈** (자원 N배 증설) |

→ A 와 C 의 공통점: *결과 (990명 실패)* 는 같은데 *불필요한 자원* 만 쓴다. B 만 *시간 0, 자원 0* 으로 같은 결과 달성.

---

## 결정 2. 커넥션 풀 / 트랜잭션 timeout 산정

**목적**: 자원 한계를 수치로 정해 cascade 진입을 막는다.

### Hikari pool 20 — Little's Law 기반 산정

| 설정 | 값 | 근거 |
|---|---|---|
| `maximum-pool-size` | **20** | Little's Law: `L = λ(DB 도달 TPS) × W(평균 트랜잭션 시간) = 100 TPS × 0.2s = 20` |
| `minimum-idle` | 20 | min = max → 트래픽 spike 시 즉시 응답 |
| `connection-timeout` | 2s | cascade 진입 전 fail (default 30초는 위험) |
| `max-lifetime` | 30분 | MySQL `wait_timeout (8h)` 보다 작게 → 좀비 커넥션 방지 |
| `leak-detection-threshold` | 10s | 트랜잭션 timeout 3초 초과 점유 = 코드 누수 의심 |

---

## 결정 3. 공평성 — Redis 정상 시 보장, 장애 시 생존 우선

**목적**: *먼저 도착한 사용자가 먼저 처리받는* 시간 공평성 (FIFO) 을 어디까지 보장할지 정의한다.

### 보장 수준

| 시나리오 | 공평성 | 메커니즘 |
|---|---|---|
| **Redis 정상** | 100% (FIFO) | `reserve_stock.lua` 원자 차감 |
| **Redis 다운** | 부분 | 풀 한도 안에서만 FIFO. 한도 초과 시 앞 사용자 지연이 후속 503 유발 |

### 왜 [부분 보장] 으로 두는가

완벽 공평성은 *큐 (Kafka) 도입* 또는 *Redis HA (Sentinel/Cluster)* 가 필요한데, 둘 다 *인프라 증설 제한 전제* 와 상충. `connection-timeout` 늘리는 건 cascade 위험 ↑.

→ *Redis 정상 시 공평, 장애 시 생존 우선* 선택. PG `CircuitBreaker` (`failureRateThreshold: 50%`) 가 지속 실패 시 자동 fallback 으로 *풀 회전 가속* → 후속 사용자 자리 받을 확률 ↑ (간접 보호).

---

# 챕터 2. 장애 대응 / 예외 처리

## 결정 4. Redis 장애 Fallback

**목적**: 핫패스 의존 컴포넌트 (Redis) 가 죽었을 때 서비스 유지 경로를 확보한다.

### 결정

재고 핫패스가 Redis Lua 원자 차감에 의존. Redis 다운 시 **DB `GET_LOCK` + `FOR UPDATE`** 폴백 경로로 전환. 폴백 경로가 *cascade 의 원천이 되지 않도록* 다층 보호를 둠.

전면 503 은 가용성 0, Sentinel / Cluster 인프라 HA 는 *인프라 증설 제한 전제* 와 상충.

### 폴백 흐름

```
Redis 정상:
  RateLimiter → reserve_stock.lua → DB persist (~10건)

Redis 다운:
  RateLimiter (500/node 입구 차단)
    ↓ 통과한 요청
  Redis CircuitBreaker OPEN → 폴백 경로 전환
    ↓
  DB GET_LOCK("product:{id}") → FOR UPDATE → 직렬 차감
    ↓
  @Transactional(timeout=3) — cascade 진입 차단
```

### 보호 메커니즘

| 위협 | 방어 |
|---|---|
| 1000 TPS 가 DB 직격 | RateLimiter 500/node *입구* 차단 |
| Redis 응답 지연 | `@Retry(name="redis")` 1회 재시도 후 회로 열림 |
| DB 락 보유로 정체 | `@Transactional(timeout=3)` 으로 자동 롤백 + 503 |
| 멱등 키 캐시 사라짐 | DB UNIQUE 가 백업 멱등성 보장 → 중복 INSERT 거부 + reconstruct 로 같은 응답 재구성 |

---

## 결정 5. 결제 실패 대응

**목적**: PG 장애 / 결제 실패 시 **환불 누락 없음** 을 보장한다.

### 결정

**Outbox 패턴 + 보상 sweeper** 채택. PG 호출 흔적을 별도 트랜잭션 (REQUIRES_NEW) 으로 DB 에 기록 → 메인 트랜잭션이 롤백돼도 흔적 보존 → sweeper 가 환불 보장.

즉시 에러로 끝내면 *PG charge 성공 + 트랜잭션 롤백* 케이스에서 환불이 누락됨. 자동 재시도는 *중복 결제* 위험.

### 케이스별 처리

| 실패 유형 | 즉시 동작 | 보상 |
|---|---|---|
| PG 한도 초과 (명시적 거절) | 402 (Payment Required) `payment.failed` + 롤백 | 없음 — PG charge 미발생 |
| 포인트 잔액 부족 | 402 (Payment Required) `payment.failed` + 롤백 | 없음 — PG charge 미발생 |
| PG 응답 없음 / timeout | `@Transactional(timeout=3)` → 503 (Service Unavailable) + 롤백 | `pg_call_log` (REQUIRES_NEW) → sweeper 가 환불 + 재고 복구 |
| PG 5xx 일시 장애 | `@Retry` 1회 재시도 → 실패 시 동일 흐름 | 동일 outbox 보상 |
| 앱 죽음 (PG charge 직후) | 신규 요청 503 (Service Unavailable) | `pg_call_log` PG_CHARGED 상태 → sweeper 환불 |
| 동시성 충돌 (낙관 락) | 409 (Conflict) `concurrent.modification` | 사용자 재시도 (결정 6) |

### 핵심 보장 — **환불 누락 없음**

```
[PG 호출 직전]
  pg_call_log INSERT (status=PG_CHARGED, REQUIRES_NEW)
  → 자체 트랜잭션 commit
[PG charge() 호출]
  → 성공 / 실패 / 무응답 어떤 결과든 흔적은 DB 에 남음
[메인 트랜잭션]
  → ROLLBACK 돼도 pg_call_log 는 살아남음
[보상]
  HoldExpirySweeper.refundOrphanedPgCharges
    → PG_CHARGED row 폴링 (SELECT FOR UPDATE SKIP LOCKED)
    → paymentGateway.cancel() 호출
    → markRefunded + release_stock.lua (Redis stock INCR + hold 삭제)
```

---

## 결정 6. 동시성 제어 — 비관 + 낙관 락 혼용

**목적**: 동시 요청에서 데이터 정합성 보장 — 초과판매 없음, 포인트 race 방지.

### 자원별 락 전략

| 자원 | 락 | 사유 |
|---|---|---|
| `ProductInventory` (재고) | `PESSIMISTIC_WRITE` + `@Version` 보조 | 같은 상품 동시 차감 — 충돌 극심 → FOR UPDATE 직렬화 |
| `User.point_balance` | `@Version` 단독 | 같은 사용자 동시 다중 예약 같은 희귀 케이스만 → 락 보유 비용 회피 |

---

## 결정 7. hold TTL 과 Sweeper 보상 구조

**목적**: 비정상 종료 (앱 죽음 / 네트워크 끊김) 시 점유된 자원 (hold 키, 재고) 을 자동 회수한다.

### 7-1. hold 키 TTL = 300초

| TTL | 평가 |
|---|---|
| 60 초 | sweeper 5분 backstop 과 mismatch → 4분 보상 윈도우 공백 |
| **300 초** *(채택)* | sweeper backstop 주기 (`0 */5 * * * *`) 와 정확히 정렬 |
| 600 초+ | 강한 1인 1예약 → 요구 범위 초과 |

정상 결제 시 *즉시 `release_stock.lua` 호출* (event-driven primary) → 사용자 체감 차단 없음. TTL 300초는 *비정상 종료 시 backstop sweeper 까지의 백업 만료 시간*.

### 7-2. Sweeper — 이벤트 기반 primary + polling backstop

```
[Primary]  환불 발생 → 즉시 release_stock.lua 호출
                   → hold 삭제 + Redis stock INCR (1초 내)

[Backstop] sweeper 5분마다 reconcileStock 폴링
                   → primary 가 놓친 케이스 보정
```

평균 복구 ~1초, 최악 ~5분 (이벤트 실패 시 안전망). 정상 케이스 DB 부담은 5분당 1회 SELECT 수준.

## 결정 8. PG 호출과 DB 트랜잭션의 관계

**목적**: 외부 API (PG) 를 DB 트랜잭션 안에서 호출하는 *일반적 안티패턴* 의 trade-off 와 본 시스템에서의 수용 근거를 정리한다.

### 결정

`BookingPersistence.doPersist()` 의 `@Transactional` 안에서 `pg.charge()` 호출 (분리 안 함). 두 가지 이유로 *허용 가능*:

- 정상 흐름에선 **Redis Lua 핫패스 filtering** → DB 도달은 ~10건만 → 락 보유 영향 미미
- **Outbox (`pg_call_log` REQUIRES_NEW)** 가 메인 트랜잭션 롤백돼도 PG 흔적 보존 → sweeper 가 환불 보장 → 환불 누락 없음

*2-phase 분리* (PENDING → PG → CONFIRMED) 는 Redis 다운 + 1000 TPS 폭주 극한 시나리오에서만 의미 있음. PENDING 정리 sweeper 추가 + 복잡도 ↑ 라 미도입.

---

## 결정 9. Redis 영속화 / 메모리 정책

**목적**: Redis 재시작 / 메모리 압박 시 어떤 키를 어떻게 보호할지 정의한다.

### 설정값

| 설정 | 값 | 사유 |
|---|---|---|
| `app.idempotency.ttl-seconds` | 900 (15분) | 모바일 백그라운드 복귀 / 사용자 새로고침 시나리오 커버 (24시간 PG 표준은 본 부하엔 메모리 낭비) |
| `maxmemory-policy` | `volatile-lru` | TTL 있는 키 (idempotency, hold) 만 LRU 삭제 → *stock 키 (TTL 없음) 영구 보호* |
| `maxmemory` | 256mb | 평시 ~9MB, 피크 ~60MB → 충분 |
| `appendonly` | `no` | 영속화 책임은 DB. 키 손실은 모두 복구 메커니즘 있음 (아래) |

### 키 손실 시 복구 — *영속화 책임은 DB*

| 키 종류 | 손실 시 처리 |
|---|---|
| stock 키 | `RedisStockBootstrap` 이 DB `product_inventory` 에서 재로드 |
| hold 키 | TTL 5분 만료처럼 처리 |
| idempotency 키 | DB UNIQUE 가 중복 차단 + `reconstruct(key)` 가 응답 재구성 |
| PG 결제 흔적 | `pg_call_log` REQUIRES_NEW 로 DB 영구 기록 → Redis 손실과 무관 |

> *AOF 미사용*: 키만 복원할 뿐 *진행 중 작업은 못 이어감*. 본 시스템 보상 sweeper 패턴엔 불필요.

### Bootstrap 전략

- **부팅 시**: `ApplicationReadyEvent` → DB → Redis 재고 동기화 (1회). Lazy load 미채택 — 첫 요청 동시 도착 시 *SET race* 위험.
- **Redis 복구 시**: `HoldExpirySweeper.reconcileStock()` 이 *활성 hold 없는 상품만* 자동 재초기화 (race 방지).

---

# 챕터 3. 결제수단 확장

## 결정 10. PaymentMethod 인터페이스 + Validator 분리

**목적**: 새 결제수단 추가 시 Booking 비즈니스 로직 수정을 최소화한다 (OCP).

### 결정

결제 수단을 `PaymentMethod` 인터페이스로 추상화하고, 라인 조합 검증은 `PaymentCompositionValidator` 로 분리. Booking 로직은 *구체 수단 타입* 에 의존하지 않고 *추상화된 인터페이스와 검증 결과만* 사용한다.

```
PaymentMethod (interface)
 ├─ CreditCardPayment
 ├─ YPayPayment
 └─ YPointPayment  ← Spring 이 List<PaymentMethod> 자동 주입

PaymentCompositionValidator: 라인 조합 룰 검증 (MAIN/SUB 충돌)
```

### 새 결제수단 추가 시 영향 범위

| 단계 | 변경 위치 |
|---|---|
| 1. enum 추가 | `PaymentMethodType` (1 토큰) |
| 2. 구현체 작성 | `XPaymentMethod implements PaymentMethod` (1 클래스) |
| 3. 등록 | `@Component` (1 줄 → Spring 자동 주입) |

→ **Booking / Checkout / Controller 코드 변경 없음**.
