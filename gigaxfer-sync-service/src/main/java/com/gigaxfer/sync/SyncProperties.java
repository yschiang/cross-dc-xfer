package com.gigaxfer.sync;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 本機部署參數（非 config 版本的一部分）：Node 名、設定目錄、自身 token 檔、NFS root 與執行器參數。 */
@ConfigurationProperties(prefix = "gigaxfer")
public record SyncProperties(
    String node,
    Path configDir,
    Path tokenFile,
    Path nfsRoot,
    Duration nfsTimeout,
    int nfsSlots,
    Long dbRetryMillis) {
}
