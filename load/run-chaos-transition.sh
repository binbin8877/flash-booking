#!/bin/bash
# 동적 카오스 시나리오 — Redis 정상 → 다운 → 복구 transition 자동화
#
# 타임라인:
#   t=0    : k6 시작 (정상 모드)
#   t=15s  : Redis pause (다운 전환)
#   t=45s  : Redis unpause (복구)
#   t=75s  : k6 종료
#
# 검증:
#   - k6 결과: created_total = 10 (정합성 transition 중에도 유지)
#   - 앱 로그: CB state transition (CLOSED → OPEN → HALF_OPEN → CLOSED)

set -e

NETWORK="${NETWORK:-booking_default}"
REDIS_CONTAINER="${REDIS_CONTAINER:-booking-redis}"

echo "[$(date +%T)] === Redis 상태 확인 ==="
if ! docker exec "$REDIS_CONTAINER" redis-cli PING > /dev/null 2>&1; then
  echo "ERROR: $REDIS_CONTAINER 가 살아있지 않습니다. docker compose up -d 먼저 실행."
  exit 1
fi

echo "[$(date +%T)] === k6 백그라운드 시작 ==="
docker run --rm -i --network "$NETWORK" \
  -e BASE_URL=http://booking-nginx \
  --name k6-chaos-transition \
  grafana/k6 run - < load/booking-burst-chaos-transition.js &
K6_PID=$!

echo "[$(date +%T)] k6 PID=$K6_PID. 15초 후 Redis pause"
sleep 15

echo "[$(date +%T)] [pause] Redis pause (다운 전환)"
docker pause "$REDIS_CONTAINER"

echo "[$(date +%T)] 30초 카오스 모드 — CB OPEN → DB 폴백 경로"
sleep 30

echo "[$(date +%T)] [unpause] Redis unpause (복구 — CB HALF_OPEN → CLOSED 자동 회복)"
docker unpause "$REDIS_CONTAINER"

echo "[$(date +%T)] 30초 회복 모드 — Lua 핫패스 복귀 검증"

wait $K6_PID
echo "[$(date +%T)] === k6 완료 ==="

echo
echo "Redis 최종 상태:"
docker exec "$REDIS_CONTAINER" redis-cli PING

echo
echo "앱 CB state transition 로그 (최근 30줄):"
docker logs booking-app1 2>&1 | grep -iE "state transition|circuit|RedisUnavailable" | tail -30 || true
