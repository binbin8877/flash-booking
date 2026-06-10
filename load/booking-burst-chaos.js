// k6 카오스 부하 테스트: Redis 다운 환경에서 DB 폴백 경로 검증
//
// 정상 환경 (booking-burst.js) 과의 차이:
//   - Redis 가 다운된 상태에서 모든 요청이 DB 폴백 경로 (GET_LOCK + FOR UPDATE) 로 진행
//   - 목표 TPS 50 — task 명시 *평시 부하* 와 매칭 (Redis 장애 시는 평시 견디기가 요구)
//   - p95 latency 한도 5초 (DB FOR UPDATE 큐 영향)
//
// 검증 포인트:
//   - 초과판매 0 (created_total = 시드 재고)
//   - Redis 죽어도 시스템 정상 응답 (5xx 없음)
//
// 실행 절차:
//   1. docker compose up -d
//   2. docker exec -i booking-mysql mysql -ubooking -pbookingpw booking < load/seed-users.sql
//   3. docker pause booking-redis                           # Redis 다운 시뮬레이션
//   4. docker run --rm -i --network booking_default \
//        -e BASE_URL=http://booking-nginx \
//        grafana/k6 run - < load/booking-burst-chaos.js
//   5. docker unpause booking-redis                         # 복구 (테스트 종료 후 잊지 말 것)

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const created = new Counter('created_total');
const soldOut = new Counter('sold_out_total');
const conflict = new Counter('conflict_total');
const unavailable = new Counter('unavailable_total');  // 503 (Redis 다운 시 일부 발생 가능)
const errors = new Counter('errors_total');
const latency = new Trend('booking_latency_ms', true);

export const options = {
  scenarios: {
    chaos_burst: {
      executor: 'ramping-arrival-rate',
      startRate: 25,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      stages: [
        { duration: '5s',  target: 25 },     // 워밍업
        { duration: '5s',  target: 50 },     // 평시 부하까지
        { duration: '30s', target: 50 },     // 평시 부하 지속 (장애 환경)
        { duration: '5s',  target: 25 },     // 회복
      ],
    },
  },
  thresholds: {
    // DB 폴백은 정상 환경 (3초) 보다 느림 — FOR UPDATE 큐 영향
    'http_req_duration': ['p(95)<5000'],
    // 핵심: 초과판매 0 (DB FOR UPDATE 가 정합성 보장)
    'created_total': ['count==10'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost';
const PRODUCT_ID = parseInt(__ENV.PRODUCT_ID || '1', 10);

export default function () {
  // 사용자 풀: load/seed-users.sql 로 INSERT 한 id 100~1099 (1,000명)
  const userId = 100 + Math.floor(Math.random() * 1000);
  const headers = {
    'Content-Type': 'application/json',
    'X-User-Id': String(userId),
    'Idempotency-Key': uuidv4(),
  };
  const body = JSON.stringify({
    productId: PRODUCT_ID,
    payment: {
      lines: [
        { method: 'CREDIT_CARD', amount: 50000, attributes: { cardToken: 'tok_visa' } },
      ],
    },
  });

  const start = Date.now();
  const res = http.post(`${BASE}/api/v1/bookings`, body, { headers, tags: { endpoint: 'booking' } });
  latency.add(Date.now() - start);

  switch (res.status) {
    case 201: created.add(1); break;
    case 410: soldOut.add(1); break;
    case 409: conflict.add(1); break;
    case 503: unavailable.add(1); break;
    default:
      if (res.status >= 400) errors.add(1);
  }

  check(res, {
    'response within 8s': r => r.timings.duration < 8000,
    'no 5xx other than 503': r => r.status < 500 || r.status === 503,
  });
}

export function handleSummary(data) {
  const m = data.metrics;
  const summary = {
    'created_total': m.created_total ? m.created_total.values.count : 0,
    'sold_out_total': m.sold_out_total ? m.sold_out_total.values.count : 0,
    'conflict_total': m.conflict_total ? m.conflict_total.values.count : 0,
    'unavailable_total': m.unavailable_total ? m.unavailable_total.values.count : 0,
    'errors_total': m.errors_total ? m.errors_total.values.count : 0,
    'http_reqs': m.http_reqs.values.count,
    'http_req_duration_p95_ms': m.http_req_duration.values['p(95)'],
    'http_req_duration_avg_ms': m.http_req_duration.values.avg,
  };
  return {
    'stdout': formatText(summary),
  };
}

function formatText(s) {
  return `\n=== Chaos Burst Result (Redis DOWN) ===\n` +
    Object.entries(s).map(([k, v]) => `  ${k}: ${typeof v === 'number' ? v.toFixed(2) : v}`).join('\n') +
    `\n\n초과판매 검증: created_total (${s.created_total}) 가 시드 재고와 같아야 합니다.\n` +
    `Redis 복구 잊지 말 것: docker unpause booking-redis\n`;
}
