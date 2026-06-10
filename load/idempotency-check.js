// 멱등성 검증: 동일 키로 10번 동시 호출 → 정확히 1건만 처리
//
// 실행:
//   k6 run load/idempotency-check.js

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { SharedArray } from 'k6/data';

const created = new Counter('created_total');
const cached = new Counter('cached_total');
const conflict = new Counter('conflict_total');
const other = new Counter('other_total');

export const options = {
  scenarios: {
    duplicate_key: {
      executor: 'per-vu-iterations',
      vus: 10,
      iterations: 1,
    },
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost';

// 모든 VU 가 같은 idempotency key 사용
const sharedKey = new SharedArray('idem-key', () => [uuidv4()]);

export default function () {
  const key = sharedKey[0];
  const headers = {
    'Content-Type': 'application/json',
    'X-User-Id': '1',
    'Idempotency-Key': key,
  };
  const body = JSON.stringify({
    productId: 1,
    payment: {
      lines: [
        { method: 'CREDIT_CARD', amount: 50000, attributes: { cardToken: 'tok_visa' } },
      ],
    },
  });

  const res = http.post(`${BASE}/api/v1/bookings`, body, { headers });
  switch (res.status) {
    case 201: created.add(1); break;
    case 200: cached.add(1); break;
    case 409: conflict.add(1); break;
    default: other.add(1);
  }
  check(res, { 'is 2xx or 409': r => [200, 201, 409].includes(r.status) });
}

export function handleSummary(data) {
  const m = data.metrics;
  const c = m.created_total ? m.created_total.values.count : 0;
  const ca = m.cached_total ? m.cached_total.values.count : 0;
  const co = m.conflict_total ? m.conflict_total.values.count : 0;
  return {
    'stdout': `\n=== Idempotency Check ===\n  created (201): ${c}\n  cached  (200): ${ca}\n  conflict(409): ${co}\n\n` +
              `검증: created 는 정확히 1 이어야 합니다.\n`,
  };
}
