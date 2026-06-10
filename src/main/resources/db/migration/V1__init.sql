CREATE TABLE product (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
    name            VARCHAR(200) NOT NULL,
    price           INT          NOT NULL,
    check_in_at     DATETIME     NOT NULL,
    check_out_at    DATETIME     NOT NULL,
    total_stock     INT          NOT NULL,
    opened_at       DATETIME     NOT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product_inventory (
    product_id      BIGINT       PRIMARY KEY,
    remaining_stock INT          NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_inv_product FOREIGN KEY (product_id) REFERENCES product(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE users (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
    name            VARCHAR(100) NOT NULL,
    point_balance   INT          NOT NULL DEFAULT 0,
    version         BIGINT       NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE booking (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
    idempotency_key VARCHAR(64)  NOT NULL,
    user_id         BIGINT       NOT NULL,
    product_id      BIGINT       NOT NULL,
    total_amount    INT          NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at    DATETIME     NULL,
    UNIQUE KEY uk_booking_idem (idempotency_key),
    INDEX idx_booking_user (user_id),
    INDEX idx_booking_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE payment (
    id                 BIGINT       PRIMARY KEY AUTO_INCREMENT,
    booking_id         BIGINT       NOT NULL,
    total_amount       INT          NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    pg_transaction_id  VARCHAR(100) NULL,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_payment_booking (booking_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE payment_line (
    id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
    payment_id    BIGINT       NOT NULL,
    method        VARCHAR(20)  NOT NULL,
    amount        INT          NOT NULL,
    external_ref  VARCHAR(100) NULL,
    status        VARCHAR(20)  NOT NULL,
    INDEX idx_line_payment (payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE point_transaction (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    delta       INT          NOT NULL,
    reason      VARCHAR(40)  NOT NULL,
    ref_id      BIGINT       NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_pt_user (user_id),
    INDEX idx_pt_ref  (ref_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
