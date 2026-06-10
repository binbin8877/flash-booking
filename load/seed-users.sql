-- 부하 테스트 전용 사용자 1,000명 시드.
-- 시드 사용자 (id 1~3) 와 겹치지 않도록 id 100~1099 범위 사용.
--
-- 실행:
--   docker exec -i booking-mysql mysql -ubooking -pbookingpw booking < load/seed-users.sql
--
-- 멱등 실행: id 중복 시 INSERT IGNORE 로 건너뜀. 반복 실행 안전.
--
-- 사용자별 잔액 100,000원 — 카드 단독 또는 카드+포인트 복합 결제 모두 시도 가능.

INSERT IGNORE INTO users (id, name, point_balance, version)
SELECT
    100 + (a.n * 100 + b.n * 10 + c.n) AS id,
    CONCAT('load_user_', 100 + (a.n * 100 + b.n * 10 + c.n)) AS name,
    100000 AS point_balance,
    0 AS version
FROM (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a
CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
            UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) b
CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
            UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) c;

-- 검증: 1000명 확인
SELECT COUNT(*) AS load_users_total FROM users WHERE id BETWEEN 100 AND 1099;
