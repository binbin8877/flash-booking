// k6 동적 카오스 부하 테스트: Redis 정상 → 다운 → 복구 transition 검증
//
// 정적 카오스 (booking-burst-chaos.js) 와의 차이:
//   - 정적: Redis 처음부터 끝까지 다운
//   - 동적: 부하 중간에 Redis 가 죽었다가 살아남 — CB OPEN → HALF_OPEN → CLOSED 자동 회복 전체 라이프사이클 검증
//
// 부하 시간 75초:
//   0~15s   : Redis 정상 (Lua 핫패스)
//   15~45s  : Redis 다운 (bash 가 docker pause) — CB OPEN → DB 폴백
//   45~75s  : Redis 복구 (bash 가 docker unpause) — CB HALF_OPEN → CLOSED → Lua 핫패스 복귀
//
// 검증 포인트:
//   - 초과판매 0 (created_total ≤ 시드 재고)
//   - transition 중에도 5xx 거의 없음 (graceful)
//   - Redis 복구 후 후속 요청이 다시 Lua 핫패스로 흐름 (앱 로그에 CB state transition)
//
// 실행은 load/run-chaos-transition.sh 가 자동화 — bash 가 pause/unpause 타이밍 제어.

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const created = new Counter('created_total');
const soldOut = new Counter('sold_out_total');
const conflict = new Counter('conflict_total');
const unavailable = new Counter('unavailable_total');  // 503 (transition 직후 일부 가능)
const errors = new Counter('errors_total');
const latency = new Trend('booking_latency_ms', true);

export const options = {
  scenarios: {
    chaos_transition: {
      executor: 'ramping-arrival-rate',
      startRate: 25,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      stages: [
        { duration: '5s',  target: 25 },     // 워밍업
        { duration: '10s', target: 50 },     // 정상 모드 (Redis ON, ~15s 까지)
        { duration: '30s', target: 50 },     // Redis 다운 phase (~45s 까지 — bash 가 15s 시점 pause)
        { duration: '30s', target: 50 },     // Redis 복구 phase (~75s 까지 — bash 가 45s 시점 unpause)
      ],
    },
  },
  thresholds: {
    // transition 중 일시 지연 허용 (CB OPEN 직전 ~수 초 timeout)
    'http_req_duration': ['p(95)<5000'],
    // 핵심: 초과판매 0 (정합성은 transition 중에도 깨지면 안 됨)
    'created_total': ['count==10'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost';
const PRODUCT_ID = parseInt(__ENV.PRODUCT_ID || '1', 10);

export default function () {
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
  return `\n=== Chaos Transition Result (Redis ON → DOWN → ON) ===\n` +
    Object.entries(s).map(([k, v]) => `  ${k}: ${typeof v === 'number' ? v.toFixed(2) : v}`).join('\n') +
    `\n\n초과판매 검증: created_total (${s.created_total}) 가 시드 재고와 같아야 합니다.\n` +
    `transition 동작은 앱 로그의 CB state transition 으로 추가 검증:\n` +
    `  docker logs booking-app1 2>&1 | grep -iE 'state transition|circuit'\n`;
}
