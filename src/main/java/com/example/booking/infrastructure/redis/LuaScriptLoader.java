package com.example.booking.infrastructure.redis;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class LuaScriptLoader {

    private RedisScript<Long> reserveStockScript;
    private RedisScript<Long> releaseStockScript;
    private RedisScript<Long> reconcileStockScript;

    @PostConstruct
    void load() throws IOException {
        this.reserveStockScript = load("lua/reserve_stock.lua");
        this.releaseStockScript = load("lua/release_stock.lua");
        this.reconcileStockScript = load("lua/reconcile_stock.lua");
    }

    public RedisScript<Long> reserveStock() {
        return reserveStockScript;
    }

    public RedisScript<Long> releaseStock() {
        return releaseStockScript;
    }

    public RedisScript<Long> reconcileStock() {
        return reconcileStockScript;
    }

    private RedisScript<Long> load(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            String body = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setScriptText(body);
            script.setResultType(Long.class);
            return script;
        }
    }
}
