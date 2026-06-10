package com.example.booking;

import com.example.booking.config.HoldProperties;
import com.example.booking.config.IdempotencyProperties;
import com.example.booking.config.SweeperProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        HoldProperties.class,
        IdempotencyProperties.class,
        SweeperProperties.class
})
public class BookingApplication {
    public static void main(String[] args) {
        SpringApplication.run(BookingApplication.class, args);
    }
}
