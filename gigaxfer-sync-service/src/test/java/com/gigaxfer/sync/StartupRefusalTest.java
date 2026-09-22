package com.gigaxfer.sync;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** P02-01：本 Node 不在 Policy 的 Node 集合，或無有效設定 → context 啟動失敗（不是靜默降級）。 */
class StartupRefusalTest {
    @TempDir Path root;

    // 不啟用 "test" profile：application-test.yml 固定 gigaxfer.node=P1，
    // 而 SpringApplicationBuilder.properties(...) 只註冊為 defaultProperties（最低優先權），
    // 會被它蓋掉，讓下面 P9 的案例測不到真正的拒絕路徑。改直接指定所需屬性（含 H2 datasource）。
    private SpringApplicationBuilder app(String node) throws Exception {
        Path cfg = Files.createDirectories(root.resolve("config"));
        Files.writeString(cfg.resolve("active.json"), SyncTestSupport.fixture());
        Files.writeString(root.resolve("token"), "p1-secret\n");
        return new SpringApplicationBuilder(GigaxferSyncApplication.class)
            .web(WebApplicationType.NONE)
            .properties(
                "gigaxfer.node=" + node,
                "gigaxfer.config-dir=" + cfg,
                "gigaxfer.token-file=" + root.resolve("token"),
                "gigaxfer.nfs-root=" + Files.createDirectories(root.resolve("nfs")),
                "spring.datasource.url=jdbc:h2:mem:refusal;MODE=Oracle;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=");
    }

    @Test
    void node_not_in_policy_refuses_to_start() throws Exception {
        assertThatThrownBy(() -> app("P9").run().close())
            .hasStackTraceContaining("is not in policy.nodes");
    }

    @Test
    void missing_active_and_lkg_refuses_to_start() throws Exception {
        SpringApplicationBuilder b = app("P1");
        Files.delete(root.resolve("config").resolve("active.json"));
        assertThatThrownBy(() -> b.run().close())
            .hasStackTraceContaining("initialise active.json first");
    }
}
