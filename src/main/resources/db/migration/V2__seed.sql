INSERT INTO product (id, name, price, check_in_at, check_out_at, total_stock, opened_at)
VALUES
    (1, '시그니처 시티뷰 1박', 50000, '2026-06-10 15:00:00', '2026-06-11 11:00:00', 10, '2026-06-06 00:00:00'),
    (2, '프리미엄 오션뷰 1박', 80000, '2026-06-12 15:00:00', '2026-06-13 11:00:00', 10, '2026-06-07 00:00:00');

INSERT INTO product_inventory (product_id, remaining_stock)
VALUES (1, 10), (2, 10);

INSERT INTO users (id, name, point_balance)
VALUES
    (1, '홍길동', 10000),
    (2, '김영희', 50000),
    (3, '이철수', 0);
