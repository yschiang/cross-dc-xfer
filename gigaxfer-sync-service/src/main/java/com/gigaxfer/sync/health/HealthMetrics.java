package com.gigaxfer.sync.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * db_health / storage_health gauge（monitoring.md Availability 軸第二層）。
 * Supplier-backed：值在 scrape 當下（`/actuator/prometheus` 讀取時）呼叫對應 indicator 探測一次，
 * 不依賴先前是否有人呼叫過 `/actuator/health/*`（否則永遠讀到 0）。探測經 indicator 自身的有界執行器，成本一 stat。
 */
@Component
public class HealthMetrics {
    public HealthMetrics(MeterRegistry registry, DbHealthIndicator db, NfsHealthIndicator nfs) {
        Gauge.builder("db_health", db, i -> i.health().getStatus() == Status.UP ? 1 : 0)
            .description("1 = DB migrated and reachable").register(registry);
        Gauge.builder("storage_health", nfs, i -> i.health().getStatus() == Status.UP ? 1 : 0)
            .description("1 = NFS root stat succeeded").register(registry);
    }
}
