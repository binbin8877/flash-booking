package com.example.booking;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Objects;

/**
 * 모든 통합 테스트의 베이스.
 * MySQL 8 + Redis 7 컨테이너를 클래스 단위 정적 필드로 한 번만 기동하여 재사용.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
public abstract class IntegrationTestBase {

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("booking")
            .withUsername("booking")
            .withPassword("bookingpw")
            .withReuse(true);

    @SuppressWarnings({"resource", "rawtypes"})
    static final GenericContainer REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(true);

    static {
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 매 테스트마다 Redis 와 MySQL 상태를 초기화 — 테스트 간 누수 차단.
     * 자식 클래스의 @BeforeEach (stockCounter.initialize 등) 보다 먼저 실행됨.
     *
     * MySQL: 트랜잭션성 데이터(booking/payment/pg_call_log/point_transaction) 만 비우고
     *        users.point_balance 와 product_inventory.remaining_stock 은 시드값으로 reset.
     *        시드 마스터(product, users 행 자체) 는 보존.
     */
    @BeforeEach
    void resetState() {
        try (RedisConnection conn = Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection()) {
            conn.serverCommands().flushDb();
        }
        jdbcTemplate.update("DELETE FROM payment_line");
        jdbcTemplate.update("DELETE FROM payment");
        jdbcTemplate.update("DELETE FROM pg_call_log");
        jdbcTemplate.update("DELETE FROM point_transaction");
        jdbcTemplate.update("DELETE FROM booking");
        jdbcTemplate.update("UPDATE users SET point_balance = 10000, version = 0 WHERE id = 1");
        jdbcTemplate.update("UPDATE users SET point_balance = 50000, version = 0 WHERE id = 2");
        jdbcTemplate.update("UPDATE users SET point_balance = 0, version = 0 WHERE id = 3");
        jdbcTemplate.update("UPDATE product_inventory SET remaining_stock = 10, version = 0 WHERE product_id IN (1, 2)");
    }
}
