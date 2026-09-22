package com.gigaxfer.core.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gigaxfer.core.identity.FileIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestCodecTest {
    final ManifestCodec codec = new ManifestCodec();
    final Manifest m = new Manifest(1, "P3", "mes", "metrology", "L123-R2", 1048576L,
        "sha256:" + "ab".repeat(32), "11111111-2222-3333-4444-555555555555",
        Instant.parse("2026-09-22T08:15:03.123Z"), "P3/mes/metrology/2026-09-22/08/L123-R2");

    /** 整行釘住：欄位順序、snake_case 名稱、欄位集合（恰 10 欄，無多餘）與結尾換行都是 wire format。 */
    @Test
    void encodes_exactly_one_snake_case_json_line() {
        assertThat(new String(codec.encode(m), StandardCharsets.UTF_8)).isEqualTo(
            "{\"schema_version\":1,\"source_node\":\"P3\",\"namespace\":\"mes\",\"data_class\":\"metrology\","
                + "\"logical_key\":\"L123-R2\",\"size\":1048576,\"digest\":\"sha256:" + "ab".repeat(32) + "\","
                + "\"uuid\":\"11111111-2222-3333-4444-555555555555\",\"source_ready_at\":\"2026-09-22T08:15:03.123Z\","
                + "\"content_path\":\"P3/mes/metrology/2026-09-22/08/L123-R2\"}\n");
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

    /** P01-03：primitive 欄位缺席時 Jackson 預設補 0，缺 size 的宣告不得被當成 size=0 的有效宣告。 */
    @Test
    void missing_size_is_malformed() {
        String s = new String(codec.encode(m), StandardCharsets.UTF_8).replace("\"size\":1048576,", "");
        assertThatThrownBy(() -> codec.decode(s.getBytes(StandardCharsets.UTF_8))).isInstanceOf(MalformedManifestException.class);
    }

    @Test
    void null_size_is_malformed() {
        String s = new String(codec.encode(m), StandardCharsets.UTF_8).replace("\"size\":1048576", "\"size\":null");
        assertThatThrownBy(() -> codec.decode(s.getBytes(StandardCharsets.UTF_8))).isInstanceOf(MalformedManifestException.class);
    }

    @Test
    void missing_schema_version_is_malformed() {
        String s = new String(codec.encode(m), StandardCharsets.UTF_8).replace("\"schema_version\":1,", "");
        assertThatThrownBy(() -> codec.decode(s.getBytes(StandardCharsets.UTF_8))).isInstanceOf(MalformedManifestException.class);
    }

    static Stream<String> requiredFields() {
        return Stream.of("schema_version", "source_node", "namespace", "data_class", "logical_key", "size",
            "digest", "uuid", "source_ready_at", "content_path");
    }

    /** 固定 wire schema 的十個欄位逐一驗證；任何欄位缺席都不是可向前相容的宣告。 */
    @ParameterizedTest
    @MethodSource("requiredFields")
    void every_required_field_must_be_present(String field) throws Exception {
        ObjectNode json = (ObjectNode) new ObjectMapper().readTree(codec.encode(m));
        json.remove(field);
        assertThatThrownBy(() -> codec.decode(json.toString().getBytes(StandardCharsets.UTF_8)))
            .as(field)
            .isInstanceOf(MalformedManifestException.class);
    }

    @ParameterizedTest
    @MethodSource("primitiveFields")
    void primitive_fields_must_not_be_null(String field) throws Exception {
        ObjectNode json = (ObjectNode) new ObjectMapper().readTree(codec.encode(m));
        json.putNull(field);
        assertThatThrownBy(() -> codec.decode(json.toString().getBytes(StandardCharsets.UTF_8)))
            .as(field)
            .isInstanceOf(MalformedManifestException.class);
    }

    static Stream<String> primitiveFields() {
        return Stream.of("schema_version", "size");
    }

    @Test
    void unknown_field_is_malformed() throws Exception {
        ObjectNode json = (ObjectNode) new ObjectMapper().readTree(codec.encode(m));
        json.put("future_field", "must not be ignored");
        assertThatThrownBy(() -> codec.decode(json.toString().getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(MalformedManifestException.class);
    }

    /** 型別不符也是損壞：不得把 "12" 或 1.5 強制轉成 size。 */
    @Test
    void non_integer_size_is_malformed() {
        for (String bad : new String[]{"\"size\":\"1048576\"", "\"size\":1048576.5"}) {
            String s = new String(codec.encode(m), StandardCharsets.UTF_8).replace("\"size\":1048576", bad);
            assertThatThrownBy(() -> codec.decode(s.getBytes(StandardCharsets.UTF_8))).as(bad).isInstanceOf(MalformedManifestException.class);
        }
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

    /** P01-03：content_path 只能是本宣告 identity + data class 推導出的位置，只有日／小時目錄可變。 */
    @Test
    void content_path_not_derived_from_identity_and_class_is_malformed() {
        String ok = "P3/mes/metrology/2026-09-22/08/L123-R2";
        for (String bad : new String[]{
            "P4/mes/metrology/2026-09-22/08/L123-R2",     // 別的 source
            "P3/other/metrology/2026-09-22/08/L123-R2",   // 別的 namespace
            "P3/mes/other/2026-09-22/08/L123-R2",         // 別的 data class
            "P3/mes/metrology/2026-09-22/08/OTHER",       // 別的 key
            "P3/mes/metrology/2026-09-22/08/../L123-R2",  // 多一段
            "P3/mes/metrology/08/L123-R2",                // 少一段
            "P3/mes/metrology/2026-02-30/08/L123-R2",     // 不存在的日期
            "P3/mes/metrology/2026-09-22/24/L123-R2",     // 不存在的小時
            "P3/mes/metrology/2026-09-22/8/L123-R2",      // 小時不是兩位
            "/P3/mes/metrology/2026-09-22/08/L123-R2",    // 絕對路徑
        }) {
            String s = new String(codec.encode(m), StandardCharsets.UTF_8).replace(ok, bad);
            assertThatThrownBy(() -> codec.decode(s.getBytes(StandardCharsets.UTF_8))).as(bad).isInstanceOf(MalformedManifestException.class);
        }
    }

    /** manifest 是單行 JSON；超過上限就是損壞宣告，即使尾端只是空白、JSON 本身仍可解析。 */
    @Test
    void oversized_manifest_is_malformed_even_if_parseable() {
        String line = new String(codec.encode(m), StandardCharsets.UTF_8);
        byte[] big = (line.strip() + " ".repeat(ManifestCodec.MAX_BYTES) + "\n").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> codec.decode(big)).isInstanceOf(MalformedManifestException.class);
        assertThatThrownBy(() -> codec.read(new java.io.ByteArrayInputStream(big))).isInstanceOf(MalformedManifestException.class);
    }

    @Test
    void read_from_stream_round_trips() throws Exception {
        assertThat(codec.read(new java.io.ByteArrayInputStream(codec.encode(m)))).isEqualTo(m);
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
