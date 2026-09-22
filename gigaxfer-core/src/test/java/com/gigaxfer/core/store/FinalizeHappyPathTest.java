package com.gigaxfer.core.store;

import com.gigaxfer.core.digest.Sha256;
import com.gigaxfer.core.identity.FileIdentity;
import com.gigaxfer.core.layout.PathLayout;
import com.gigaxfer.core.manifest.Manifest;
import com.gigaxfer.core.manifest.ManifestCodec;
import com.gigaxfer.core.nfs.BoundedNfsExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FinalizeHappyPathTest {
    @TempDir Path root;
    MutableClock clock = new MutableClock(Instant.parse("2026-09-22T08:15:03Z"));
    BoundedNfsExecutor nfs;
    PathLayout layout;
    LocalStore store;
    final FileIdentity id = new FileIdentity("P3", "mes", "L123-R2");

    @BeforeEach
    void setUp() {
        nfs = new BoundedNfsExecutor("t", 4, Duration.ofSeconds(5));
        layout = new PathLayout(root, ZoneOffset.UTC);
        store = new LocalStore("P3", layout, nfs, WriteGate.open(), clock);
    }

    @AfterEach
    void tearDown() {
        nfs.close();
    }

    @Test
    void publishes_key_and_manifest_and_cleans_temps() throws Exception {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        WriteHandle h = store.beginWrite("mes", "metrology", "L123-R2");
        h.stream().write(content);

        FinalizeResult r = h.finalizeWrite();

        assertThat(r).isInstanceOf(FinalizeResult.Success.class);
        FinalizeResult.Success s = (FinalizeResult.Success) r;
        assertThat(s.identity()).isEqualTo(id);
        assertThat(s.contentPath()).isEqualTo("P3/mes/metrology/2026-09-22/08/L123-R2");

        Path key = root.resolve("P3/mes/metrology/2026-09-22/08/L123-R2");
        assertThat(key).hasBinaryContent(content);

        Manifest m = new ManifestCodec().decode(Files.readAllBytes(layout.manifestPath(id)));
        assertThat(m.size()).isEqualTo(5);
        assertThat(m.digest()).isEqualTo(Sha256.ofBytes(content));
        assertThat(m.dataClass()).isEqualTo("metrology");
        assertThat(m.sourceReadyAt()).isEqualTo(clock.instant());
        assertThat(m.contentPath()).isEqualTo(s.contentPath());
        assertThat(m.uuid()).isEqualTo(h.uuid().toString());

        try (Stream<Path> s1 = Files.list(key.getParent())) {
            assertThat(s1).containsExactly(key); // 沒有 .writing 殘留
        }
        try (Stream<Path> s2 = Files.list(layout.manifestDir(id))) {
            assertThat(s2).containsExactly(layout.manifestPath(id)); // 沒有 .tmp 殘留
        }
    }

    @Test
    void empty_file_is_a_valid_ready_file() throws Exception {
        WriteHandle h = store.beginWrite("mes", "metrology", "EMPTY");
        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.Success.class);
        assertThat(root.resolve("P3/mes/metrology/2026-09-22/08/EMPTY")).exists().isEmptyFile();
    }

    @Test
    void large_content_streams_without_buffering_whole_file() throws Exception {
        byte[] chunk = new byte[1 << 20]; // 1 MB × 8 = 8 MB
        for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) i;
        WriteHandle h = store.beginWrite("mes", "metrology", "BIG");
        for (int i = 0; i < 8; i++) h.stream().write(chunk);
        FinalizeResult r = h.finalizeWrite();
        assertThat(r).isInstanceOf(FinalizeResult.Success.class);
        Path key = root.resolve("P3/mes/metrology/2026-09-22/08/BIG");
        assertThat(Files.size(key)).isEqualTo(8L << 20);
        Manifest m = new ManifestCodec().decode(Files.readAllBytes(layout.manifestPath(new FileIdentity("P3", "mes", "BIG"))));
        assertThat(m.digest()).isEqualTo(Sha256.ofFile(key));
    }

    @Test
    void finalize_twice_on_same_handle_is_idempotent_success() throws Exception {
        WriteHandle h = store.beginWrite("mes", "metrology", "L123-R2");
        h.stream().write("x".getBytes(StandardCharsets.UTF_8));
        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.Success.class);
        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.Success.class);
        assertThat(root.resolve("P3/mes/metrology/2026-09-22/08/L123-R2")).hasContent("x");
    }
}
