package com.gigaxfer.core.layout;

import com.gigaxfer.core.digest.Sha256;
import com.gigaxfer.core.identity.FileIdentity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathLayoutTest {
    final Path root = Path.of("/nas");
    final PathLayout layout = new PathLayout(root, ZoneOffset.UTC);
    final FileIdentity id = new FileIdentity("P3", "mes", "L123-R2");
    final Instant at = Instant.parse("2026-09-22T08:15:03Z");

    @Test
    void content_dir_is_source_ns_class_day_hour() {
        assertThat(layout.contentDir(id, "metrology", at))
            .isEqualTo(root.resolve("P3/mes/metrology/2026-09-22/08"));
    }

    @Test
    void content_dir_uses_configured_zone() {
        PathLayout taipei = new PathLayout(root, java.time.ZoneId.of("Asia/Taipei"));
        assertThat(taipei.contentDir(id, "metrology", at))
            .isEqualTo(root.resolve("P3/mes/metrology/2026-09-22/16"));
    }

    @Test
    void manifest_lives_in_hash_bucket_independent_of_time() {
        String bucket = PathLayout.bucket("L123-R2");
        assertThat(bucket).hasSize(3).matches("[0-9a-f]{3}");
        String expected = Sha256.ofBytes("L123-R2".getBytes(StandardCharsets.UTF_8)).substring(Sha256.PREFIX.length(), Sha256.PREFIX.length() + 3);
        assertThat(bucket).isEqualTo(expected);
        assertThat(layout.manifestPath(id)).isEqualTo(root.resolve("P3/mes/.manifest/" + bucket + "/L123-R2.manifest"));
    }

    @Test
    void temp_names_carry_uuid() {
        UUID u = UUID.fromString("11111111-2222-3333-4444-555555555555");
        assertThat(layout.writingPath(Path.of("/nas/x"), id, u)).isEqualTo(Path.of("/nas/x/L123-R2." + u + ".writing"));
        assertThat(layout.manifestTmpPath(id, u)).isEqualTo(layout.manifestDir(id).resolve("L123-R2.manifest." + u + ".tmp"));
    }

    @Test
    void content_path_round_trips_relative_to_root() {
        Path abs = layout.contentDir(id, "metrology", at).resolve("L123-R2");
        String rel = layout.toContentPath(abs);
        assertThat(rel).isEqualTo("P3/mes/metrology/2026-09-22/08/L123-R2");
        assertThat(layout.fromContentPath(rel)).isEqualTo(abs);
    }

    @Test
    void from_content_path_rejects_escape() {
        assertThatThrownBy(() -> layout.fromContentPath("../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
    }
}
