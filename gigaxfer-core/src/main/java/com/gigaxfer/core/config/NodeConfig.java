package com.gigaxfer.core.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;

/** 一個不可變的設定版本（git configs/v<N>.json = sync 主機 active.json）。fixed 省略時解碼為 V1。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record NodeConfig(
    int schemaVersion,
    long version,
    String publishedBy,
    Instant publishedAt,
    Policy policy,
    OperationalPolicy operational,
    FixedConstants fixed) {
}
