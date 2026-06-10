// k6 부하 테스트: 00시 오픈 상황 시뮬레이션
// 평시 50 TPS → 5초 안에 1000 TPS 로 급증 → 60초 유지 → 점진 감소
//
// 실행:
//   docker compose up -d                            # 앱 기동 (시드: product#1 재고 10)
//   docker exec -i booking-mysql mysql -ubooking -pbookingpw booking < load/seed-users.sql
//                                                   # 사용자 1,000명 시드 (id 100~1099)
//   k6 run load/booking-burst.js                    # k6 설치 필요 (https://k6.io)
//
// 결과 검증:
//   created_total (201) 은 시드 재고와 동일해야 함 (초과판매 0)
//   p95 latency 는 1s 이하 권장
//
// 사용자 풀: id 100~1099 (1,000 명). hold:productId:userId 가 사용자별이므로
// 충분한 풀이 있어야 1000 TPS 동시 경쟁이 자연스럽게 시뮬레이션 된다.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const created = new Counter('created_total');
const soldOut = new Counter('sold_out_total');
const conflict = new Counter('conflict_total');
const errors = new Counter('errors_total');
const latency = new Trend('booking_latency_ms', true);

export const options = {
  scenarios: {
    open_burst: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 1500,
      stages: [
        { duration: '10s', target: 50 },     // 평시
        { duration: '5s',  target: 1000 },   // 오픈 직후 급증
        { duration: '60s', target: 1000 },   // 피크 유지
        { duration: '10s', target: 50 },     // 회복
      ],
    },
  },
  thresholds: {
    // 1000 TPS 환경에서 RateLimiter + Tomcat 큐 영향 고려한 현실적 기준
    'http_req_duration': ['p(95)<3000'],
    // 핵심 검증: 시드 재고와 정확히 일치 (초과판매 0)
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
    default:
      if (res.status >= 400) errors.add(1);
  }

  check(res, {
    'response within 3s': r => r.timings.duration < 3000,
    'no 5xx': r => r.status < 500,
  });
}

export function handleSummary(data) {
  const m = data.metrics;
  const summary = {
    'created_total': m.created_total ? m.created_total.values.count : 0,
    'sold_out_total': m.sold_out_total ? m.sold_out_total.values.count : 0,
    'conflict_total': m.conflict_total ? m.conflict_total.values.count : 0,
    'errors_total': m.errors_total ? m.errors_total.values.count : 0,
    'http_reqs': m.http_reqs.values.count,
    'http_req_duration_p95_ms': m.http_req_duration.values['p(95)'],
    'http_req_duration_avg_ms': m.http_req_duration.values.avg,
  };
  return {
    'stdout': formatText(summary),
    'load/last-result.json': JSON.stringify(summary, null, 2),
  };
}

function formatText(s) {
  return `\n=== Booking Burst Result ===\n` +
    Object.entries(s).map(([k, v]) => `  ${k}: ${typeof v === 'number' ? v.toFixed(2) : v}`).join('\n') +
    `\n\n초과판매 검증: created_total (${s.created_total}) 가 시드 재고와 같아야 합니다.\n`;
}
