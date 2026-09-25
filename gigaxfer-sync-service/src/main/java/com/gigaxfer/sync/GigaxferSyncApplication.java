package com.gigaxfer.sync;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SyncProperties.class)
public class GigaxferSyncApplication {
    public static void main(String[] args) {
        // 時間一律 UTC（D58 ⑦）。部署以 -Duser.timezone=UTC 啟動；沒帶時這裡仍固定為 UTC，不讓主機時區滲進 DB 時間。
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(GigaxferSyncApplication.class, args);
    }
}
