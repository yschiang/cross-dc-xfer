package com.gigaxfer.sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 每個測試類別一個 TempDir：config/active.json = fixture v3、token 檔 = "p1-secret"、nfs root = 空目錄。 */
@SpringBootTest
@ActiveProfiles("test")
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
