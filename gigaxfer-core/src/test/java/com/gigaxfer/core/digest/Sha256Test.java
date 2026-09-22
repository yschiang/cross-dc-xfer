package com.gigaxfer.core.digest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import com.gigaxfer.core.nfs.BoundedNfsExecutor;
import com.gigaxfer.core.nfs.NfsExecutor;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class Sha256Test {
    // SHA-256("abc") 的標準測試向量
    static final String ABC = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void ofBytes_matches_known_vector() {
        assertThat(Sha256.ofBytes("abc".getBytes(StandardCharsets.UTF_8))).isEqualTo(ABC);
    }

    @Test
    void ofFile_streams_and_matches_ofBytes(@TempDir Path dir) throws Exception {
        byte[] big = new byte[3 * 64 * 1024 + 17]; // 跨越多個 64 KB buffer
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 31);
        Path f = dir.resolve("big.bin");
        Files.write(f, big);
        try (BoundedNfsExecutor nfs = new BoundedNfsExecutor("t", 1, Duration.ofSeconds(5))) {
            assertThat(Sha256.ofFile(nfs, "digest", f)).isEqualTo(Sha256.ofBytes(big));
        }
    }

    /** P01-07：所有 NFS 操作經有界執行器——公開的檔案 digest 入口都必須要求 NfsExecutor。 */
    @Test
    void every_public_file_digest_entry_requires_the_nfs_executor() {
        for (Method mth : Sha256.class.getMethods()) {
            List<Class<?>> params = Arrays.asList(mth.getParameterTypes());
            if (params.contains(Path.class)) assertThat(params).as(mth.toString()).contains(NfsExecutor.class);
        }
    }

    @Test
    void format_is_prefixed_lowercase_hex() {
        String s = Sha256.ofBytes(new byte[0]);
        assertThat(s).startsWith("sha256:").hasSize(7 + 64).matches("sha256:[0-9a-f]{64}");
    }
}
