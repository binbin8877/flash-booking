-- pg_call_log 에 product_id / user_id 추가.
-- sweeper 가 orphan PG charge 환불 직후 *해당 사용자의 hold 키 즉시 해제* 하기 위함.
-- 기존 행은 NULL 허용 (역호환). 신규 INSERT 부터 채워짐.
ALTER TABLE pg_call_log
    ADD COLUMN product_id BIGINT NULL AFTER booking_id,
    ADD COLUMN user_id    BIGINT NULL AFTER product_id;
