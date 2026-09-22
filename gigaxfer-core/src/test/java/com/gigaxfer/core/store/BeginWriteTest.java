package com.gigaxfer.core.store;

import com.gigaxfer.core.layout.PathLayout;
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
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BeginWriteTest {
    @TempDir Path root;
    MutableClock clock = new MutableClock(Instant.parse("2026-09-22T08:15:03Z"));
    BoundedNfsExecutor nfs;
    PathLayout layout;

    @BeforeEach
    void setUp() {
        nfs = new BoundedNfsExecutor("t", 4, Duration.ofSeconds(5));
        layout = new PathLayout(root, ZoneOffset.UTC);
    }

    @AfterEach
    void tearDown() {
        nfs.close();
    }

    LocalStore store(WriteGate gate) {
        return new LocalStore("P3", layout, nfs, gate, clock);
    }

    @Test
    void creates_uuid_writing_file_in_current_hour_dir() throws Exception {
        WriteHandle h = store(WriteGate.open()).beginWrite("mes", "metrology", "L1");
        Path dir = root.resolve("P3/mes/metrology/2026-09-22/08");
        assertThat(h.writingPath().getParent()).isEqualTo(dir);
        assertThat(h.writingPath().getFileName().toString()).matches("L1\\.[0-9a-f-]{36}\\.writing");
        assertThat(h.writingPath()).exists();
        h.close();
    }

    @Test
    void stream_writes_bytes_to_writing_file() throws Exception {
        WriteHandle h = store(WriteGate.open()).beginWrite("mes", "metrology", "L1");
        h.stream().write("hello".getBytes(StandardCharsets.UTF_8));
        h.stream().flush();
        assertThat(h.writingPath()).hasContent("hello");
        h.close();
    }

    @Test
    void discard_removes_writing_file_and_nothing_else() throws Exception {
        WriteHandle h = store(WriteGate.open()).beginWrite("mes", "metrology", "L1");
        h.stream().write(new byte[10]);
        h.discard();
        assertThat(h.writingPath()).doesNotExist();
        try (Stream<Path> s = Files.walk(root)) {
            assertThat(s.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void gate_rejection_creates_nothing() {
        WriteGate gate = (ns, cls) -> Optional.of("data class not registered: " + cls);
        assertThatThrownBy(() -> store(gate).beginWrite("mes", "unknown", "L1"))
            .isInstanceOf(WriteRejectedException.class)
            .satisfies(e -> assertThat(((WriteRejectedException) e).reason()).isEqualTo(WriteRejectedException.Reason.REJECTED));
        assertThat(root.resolve("P3")).doesNotExist();
    }

    /** I2：dataClass 也是原始路徑片段，沒檢查就能用 ".." 逃出 namespace 樹。 */
    @Test
    void invalid_data_class_is_rejected_before_touching_nfs() throws Exception {
        for (String bad : new String[]{"../x", "", "a/b"}) {
            assertThatThrownBy(() -> store(WriteGate.open()).beginWrite("mes", bad, "L1"))
                .describedAs("dataClass=%s", bad)
                .isInstanceOf(IllegalArgumentException.class);
        }
        try (Stream<Path> s = Files.walk(root)) {
            assertThat(s.filter(Files::isRegularFile)).isEmpty();
        }
        assertThat(root.resolve("P3")).doesNotExist();
    }

    @Test
    void invalid_logical_key_is_rejected_before_touching_nfs() {
        assertThatThrownBy(() -> store(WriteGate.open()).beginWrite("mes", "metrology", "bad.writing"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(root.resolve("P3")).doesNotExist();
    }
}
