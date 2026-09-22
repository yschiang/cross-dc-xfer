package com.gigaxfer.sync.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** db_health / storage_health gauge（monitoring.md Availability 軸第二層）；由 health indicator 每次探測更新，未探測前為 0。 */
@Component
public class HealthMetrics {
    private final AtomicInteger db = new AtomicInteger();
    private final AtomicInteger storage = new AtomicInteger();

    public HealthMetrics(MeterRegistry registry) {
        Gauge.builder("db_health", db, AtomicInteger::get).description("1 = DB migrated and reachable").register(registry);
        Gauge.builder("storage_health", storage, AtomicInteger::get).description("1 = NFS root stat succeeded").register(registry);
    }

    void db(boolean up) {
        db.set(up ? 1 : 0);
    }

    void storage(boolean up) {
        storage.set(up ? 1 : 0);
    }
}
