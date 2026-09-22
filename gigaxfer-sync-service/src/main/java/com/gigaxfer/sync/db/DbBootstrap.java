package com.gigaxfer.sync.db;

import com.gigaxfer.sync.SyncProperties;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 啟動序列第二步（D34）：開 DB。DB 連不上不退出：背景重試 migration（預設每 5 s，
 * 可用 gigaxfer.db-retry-millis 覆寫，測試用），期間 health 回 DOWN（D34 修）。
 * bootstrap 列：node_meta 一列（incarnation 只在首次建立時產生，D55 (5)）、seq_counter 兩列（D29 修 11）。
 */
@Configuration
public class DbBootstrap {
    private static final Logger log = LoggerFactory.getLogger(DbBootstrap.class);
    static final long RETRY_MILLIS = 5_000;

    @Bean
    DbState dbState(DataSource ds, TransactionTemplate tx) {
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        return new DbState(() -> {
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
            tx.executeWithoutResult(s -> {
                Integer meta = jdbc.queryForObject("SELECT COUNT(*) FROM node_meta", Integer.class);
                if (meta == null || meta == 0) {
                    jdbc.update("INSERT INTO node_meta (singleton, incarnation, rebuild_in_progress) VALUES (1, ?, 0)",
                        UUID.randomUUID().toString());
                }
                for (String name : new String[] {"completed", "change"}) {
                    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM seq_counter WHERE name = ?", Integer.class, name);
                    if (n == null || n == 0) {
                        jdbc.update("INSERT INTO seq_counter (name, last) VALUES (?, 0)", name);
                    }
                }
            });
        });
    }

    @Bean
    SmartLifecycle dbBootstrapLifecycle(DbState state, SyncProperties props) {
        long retryMillis = props.dbRetryMillis() == null ? RETRY_MILLIS : props.dbRetryMillis();
        return new SmartLifecycle() {
            private volatile Thread worker;

            @Override
            public void start() {
                Thread t = new Thread(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            state.runOnce();
                            log.info("database migrated and bootstrapped");
                            return;
                        } catch (RuntimeException e) {
                            log.warn("database not ready ({}); retrying in {} ms", state.lastError().orElse("?"), retryMillis);
                        }
                        try {
                            Thread.sleep(retryMillis);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }, "db-bootstrap");
                t.setDaemon(true);
                worker = t;
                t.start();
            }

            @Override
            public void stop() {
                Thread t = worker;
                if (t != null) {
                    t.interrupt();
                }
            }

            @Override
            public boolean isRunning() {
                Thread t = worker;
                return t != null && t.isAlive();
            }

            @Override
            public int getPhase() {
                return Integer.MIN_VALUE; // 在 web server 之前啟動，但不阻擋它
            }
        };
    }
}
