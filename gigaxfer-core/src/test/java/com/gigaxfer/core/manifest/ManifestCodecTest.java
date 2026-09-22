package com.gigaxfer.core.manifest;

import com.gigaxfer.core.identity.FileIdentity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestCodecTest {
    final ManifestCodec codec = new ManifestCodec();
    final Manifest m = new Manifest(1, "P3", "mes", "metrology", "L123-R2", 1048576L,
        "sha256:" + "ab".repeat(32), "11111111-2222-3333-4444-555555555555",
        Instant.parse("2026-09-22T08:15:03.123Z"), "P3/mes/metrology/2026-09-22/08/L123-R2");

    @Test
    void encodes_single_line_snake_case_json() {
        String s = new String(codec.encode(m), StandardCharsets.UTF_8);
        assertThat(s).endsWith("\n");
        assertThat(s.substring(0, s.length() - 1)).doesNotContain("\n");
        assertThat(s).contains("\"schema_version\":1").contains("\"source_node\":\"P3\"").contains("\"data_class\":\"metrology\"")
            .contains("\"logical_key\":\"L123-R2\"").contains("\"source_ready_at\":\"2026-09-22T08:15:03.123Z\"")
            .contains("\"content_path\":\"P3/mes/metrology/2026-09-22/08/L123-R2\"");
    }

    @Test
    void round_trips() throws Exception {
        assertThat(codec.decode(codec.encode(m))).isEqualTo(m);
    }

    @Test
    void truncated_bytes_are_malformed() {
        byte[] full = codec.encode(m);
        byte[] half = Arrays.copyOf(full, full.length / 2);
        assertThatThrownBy(() -> codec.decode(half)).isInstanceOf(MalformedManifestException.class);
    }

    @Test
    void wrong_schema_version_is_malformed() {
        String s = new String(codec.encode(m), StandardCharsets.UTF_8).replace("\"schema_version\":1", "\"schema_version\":2");
        assertThatThrownBy(() -> codec.decode(s.getBytes(StandardCharsets.UTF_8))).isInstanceOf(MalformedManifestException.class);
    }

    @Test
    void missing_digest_is_malformed() {
        String s = new String(codec.encode(m), StandardCharsets.UTF_8).replace("\"digest\":\"sha256:" + "ab".repeat(32) + "\",", "");
        assertThatThrownBy(() -> codec.decode(s.getBytes(StandardCharsets.UTF_8))).isInstanceOf(MalformedManifestException.class);
    }

    @Test
    void bad_digest_is_malformed() {
        String s = new String(codec.encode(m), StandardCharsets.UTF_8)
            .replace("\"digest\":\"sha256:" + "ab".repeat(32) + "\"", "\"digest\":\"not-a-digest\"");
        assertThatThrownBy(() -> codec.decode(s.getBytes(StandardCharsets.UTF_8))).isInstanceOf(MalformedManifestException.class);
    }

    @Test
    void negative_size_is_malformed() {
        String s = new String(codec.encode(m), StandardCharsets.UTF_8).replace("\"size\":1048576", "\"size\":-1");
        assertThatThrownBy(() -> codec.decode(s.getBytes(StandardCharsets.UTF_8))).isInstanceOf(MalformedManifestException.class);
    }

    @Test
    void logical_key_with_slash_is_malformed() {
        String s = new String(codec.encode(m), StandardCharsets.UTF_8).replace("\"logical_key\":\"L123-R2\"", "\"logical_key\":\"a/b\"");
        assertThatThrownBy(() -> codec.decode(s.getBytes(StandardCharsets.UTF_8))).isInstanceOf(MalformedManifestException.class);
    }

    @Test
    void empty_data_class_is_malformed() {
        String s = new String(codec.encode(m), StandardCharsets.UTF_8).replace("\"data_class\":\"metrology\"", "\"data_class\":\"\"");
        assertThatThrownBy(() -> codec.decode(s.getBytes(StandardCharsets.UTF_8))).isInstanceOf(MalformedManifestException.class);
    }

    @Test
    void same_declaration_compares_four_fields() {
        FileIdentity id = new FileIdentity("P3", "mes", "L123-R2");
        assertThat(m.sameDeclaration(id, "metrology", 1048576L, m.digest())).isTrue();
        assertThat(m.sameDeclaration(id, "other", 1048576L, m.digest())).isFalse();
        assertThat(m.sameDeclaration(id, "metrology", 1L, m.digest())).isFalse();
        assertThat(m.sameDeclaration(id, "metrology", 1048576L, "sha256:" + "00".repeat(32))).isFalse();
        assertThat(m.sameDeclaration(new FileIdentity("P4", "mes", "L123-R2"), "metrology", 1048576L, m.digest())).isFalse();
    }
}
