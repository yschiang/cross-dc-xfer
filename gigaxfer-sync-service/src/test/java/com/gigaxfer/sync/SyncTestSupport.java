package com.gigaxfer.sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 每個測試類別一個 TempDir：config/active.json = fixture v3、token 檔 = "p1-secret"、nfs root = 空目錄。
 * {@code @DirtiesContext}：subclass 的 {@code @DynamicPropertySource} 綁的是各自 TempDir 的路徑；
 * 沒有它，設定相同的兩個測試類別會被 Spring context 快取共用，第二個類別跑到的會是第一個類別
 * 已被 JUnit 清掉的 TempDir（nfs root 對活躍 bean 如 NfsExecutor 尤其致命）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class SyncTestSupport {
    public static final String P1_TOKEN = "p1-secret";
    public static final String P2_TOKEN = "p2-secret";

    @TempDir static Path root;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        Path cfg = Files.createDirectories(root.resolve("config"));
        Path nfs = Files.createDirectories(root.resolve("nfs"));
        Files.writeString(cfg.resolve("active.json"), fixture());
        Path token = root.resolve("token");
        Files.writeString(token, P1_TOKEN + "\n");
        r.add("gigaxfer.config-dir", cfg::toString);
        r.add("gigaxfer.nfs-root", nfs::toString);
        r.add("gigaxfer.token-file", token::toString);
    }

    public static String fixture() throws IOException {
        try (InputStream in = SyncTestSupport.class.getResourceAsStream("/config/v3.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static Path configDir() {
        return root.resolve("config");
    }

    public static Path nfsRoot() {
        return root.resolve("nfs");
    }
}
