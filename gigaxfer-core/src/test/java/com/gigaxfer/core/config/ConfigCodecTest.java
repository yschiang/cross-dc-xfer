package com.gigaxfer.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigCodecTest {

    private static byte[] fixture() throws IOException {
        try (InputStream in = ConfigCodecTest.class.getResourceAsStream("/config/v3.json")) {
            return in.readAllBytes();
        }
    }

    private static byte[] mutate(String find, String replace) throws IOException {
        String s = new String(fixture(), StandardCharsets.UTF_8);
        assertThat(s).contains(find);
        return s.replace(find, replace).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void decodes_fixture_and_normalises_policy() throws IOException {
        NodeConfig c = ConfigCodec.decode(fixture());
        assertThat(c.schemaVersion()).isEqualTo(1);
        assertThat(c.version()).isEqualTo(3L);
        assertThat(c.publishedBy()).isEqualTo("alice");
        assertThat(c.policy().deployment()).isEqualTo("example-deployment");
        assertThat(c.policy().nodes()).containsExactly("P1", "P2", "P3");
        assertThat(c.policy().targetsFor("P1", "lot-log")).contains(Set.of("P2", "P3"));
        assertThat(c.policy().targetsFor("P1", "local-only")).contains(Set.of());
        assertThat(c.policy().targetsFor("P3", "lot-log")).isEmpty();
        assertThat(c.operational().pendingLimit()).isEqualTo(200);
        assertThat(c.operational().peerTokenSha256()).containsKeys("P1", "P2", "P3");
        assertThat(c.fixed()).isEqualTo(FixedConstants.V1);
    }

    @Test
    void policy_equality_ignores_list_order() throws IOException {
        NodeConfig a = ConfigCodec.decode(fixture());
        NodeConfig b = ConfigCodec.decode(mutate("[\"P1\", \"P2\", \"P3\"]", "[\"P3\", \"P1\", \"P2\"]"));
        assertThat(b.policy()).isEqualTo(a.policy());
    }

    @Test
    void fixed_segment_may_be_omitted_and_then_defaults_to_v1() throws IOException {
        String s = new String(fixture(), StandardCharsets.UTF_8);
        int i = s.indexOf("  \"fixed\"");
        String without = s.substring(0, i).replaceAll(",\\s*$", "\n") + "}\n";
        NodeConfig c = ConfigCodec.decode(without.getBytes(StandardCharsets.UTF_8));
        assertThat(c.fixed()).isEqualTo(FixedConstants.V1);
    }

    @Test
    void rejects_fixed_segment_that_differs_from_v1() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"declaration_max_age_days\": 7", "\"declaration_max_age_days\": 8")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("fixed");
    }

    @Test
    void rejects_unknown_field() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"version\": 3,", "\"version\": 3, \"extra\": 1,")))
            .isInstanceOf(InvalidConfigException.class);
    }

    @Test
    void rejects_wrong_schema_version() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"schema_version\": 1", "\"schema_version\": 2")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("schema_version");
    }

    @Test
    void rejects_target_not_in_nodes() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"targets\": [\"P1\"]", "\"targets\": [\"P9\"]")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("P9");
    }

    @Test
    void rejects_source_as_its_own_target() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"targets\": [\"P1\"]", "\"targets\": [\"P2\"]")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("P2");
    }

    @Test
    void rejects_duplicate_source_class_pair() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"data_class\": \"local-only\"", "\"data_class\": \"lot-log\"")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("lot-log");
    }

    @Test
    void rejects_empty_nodes() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("[\"P1\", \"P2\", \"P3\"]", "[]")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("nodes");
    }

    @Test
    void rejects_more_than_ten_nodes() throws IOException {
        String eleven = "[\"P1\", \"P2\", \"P3\", \"P4\", \"P5\", \"P6\", \"P7\", \"P8\", \"P9\", \"P10\", \"P11\"]";
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("[\"P1\", \"P2\", \"P3\"]", eleven)))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("nodes");
    }

    @Test
    void rejects_bad_node_name_segment() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"P3\"", "\"a/b\"")))
            .isInstanceOf(InvalidConfigException.class);
    }

    @Test
    void rejects_peer_token_for_unknown_node_and_bad_hex() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"P3\": \"fd61a03a", "\"P9\": \"fd61a03a")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("P9");
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("a770a998b69adca5f88498fcd315c7d49d54b3ab2091c6f8afab58390bb1da1a", "zz")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("sha256");
    }

    @Test
    void rejects_capacity_reject_not_below_alert() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"capacity_alert_bytes\": 21990232555520", "\"capacity_alert_bytes\": 1")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("capacity");
    }

    @Test
    void rejects_non_positive_operational_value() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"pending_limit\": 200", "\"pending_limit\": 0")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("pending_limit");
    }

    @Test
    void encode_policy_is_single_line_snake_case_json() throws IOException {
        NodeConfig c = ConfigCodec.decode(fixture());
        String json = new String(ConfigCodec.encodePolicy(c.policy()), StandardCharsets.UTF_8);
        assertThat(json).startsWith("{");
        assertThat(json).contains("\"namespaces\":[\"analytics\",\"transactions\"]");
        assertThat(json).endsWith("}\n");
        assertThat(json.strip()).doesNotContain("\n");
    }

    @Test
    void encode_then_decode_round_trips() throws IOException {
        NodeConfig c = ConfigCodec.decode(fixture());
        assertThat(ConfigCodec.decode(ConfigCodec.encode(c))).isEqualTo(c);
    }

    @Test
    void registry_rejects_unregistered_namespace_but_allows_local_only_class() throws IOException {
        Policy policy = ConfigCodec.decode(fixture()).policy();
        assertThat(policy.namespaces()).containsExactly("analytics", "transactions");
        assertThat(policy.allowsWrite("transactions", "P1", "lot-log")).isTrue();
        assertThat(policy.allowsWrite("transactions", "P1", "local-only")).isTrue();
        assertThat(policy.allowsWrite("unregistered", "P1", "lot-log")).isFalse();
        assertThat(policy.allowsWrite("transactions", "P1", "unregistered")).isFalse();
    }

    @Test
    void namespace_registry_is_validated_and_part_of_policy_identity() throws IOException {
        String entry = "\"namespaces\": [\"transactions\", \"analytics\"]";
        Policy original = ConfigCodec.decode(fixture()).policy();
        assertThat(ConfigCodec.decode(mutate(entry,
            "\"namespaces\": [\"analytics\", \"transactions\"]")).policy()).isEqualTo(original);
        assertThat(ConfigCodec.decode(mutate(entry,
            "\"namespaces\": [\"transactions\"]")).policy()).isNotEqualTo(original);
        assertThatThrownBy(() -> ConfigCodec.decode(mutate(entry,
            "\"namespaces\": [\"transactions\", \"transactions\"]")))
            .isInstanceOf(InvalidConfigException.class);
        assertThatThrownBy(() -> ConfigCodec.decode(mutate(entry,
            "\"namespaces\": [\"../escape\"]")))
            .isInstanceOf(InvalidConfigException.class);
        assertThatThrownBy(() -> ConfigCodec.decode(mutate(entry + ",", "")))
            .isInstanceOf(InvalidConfigException.class);
    }

    @Test
    void rejects_duplicate_peer_token_hash() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate(
            "\"P2\": \"cd080f7d82024533203a52d5493255eba01280b4b2c56e025f7d90bd0dcb8590\"",
            "\"P2\": \"a770a998b69adca5f88498fcd315c7d49d54b3ab2091c6f8afab58390bb1da1a\"")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("uniquely");
    }

    /** P02-05：JSON null（整份或清單元素）不得以 NPE 逃出，必須是 InvalidConfigException，呼叫端才會回退。 */
    @Test
    void rejects_json_null_root_and_null_elements_as_invalid() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode("null".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(InvalidConfigException.class);
        assertThatThrownBy(() -> ConfigCodec.decode(mutate(
            "{ \"source_node\": \"P1\", \"data_class\": \"local-only\", \"targets\": [] },", "null,")))
            .isInstanceOf(InvalidConfigException.class);
    }

    /** P02-03、D30 修 6：不做隱式型別轉換，不接受尾隨 token。 */
    @Test
    void rejects_implicit_coercion_and_trailing_tokens() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"schema_version\": 1", "\"schema_version\": 1.9")))
            .isInstanceOf(InvalidConfigException.class);
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"search_window_days\": 30", "\"search_window_days\": 30.9")))
            .isInstanceOf(InvalidConfigException.class);
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"version\": 3", "\"version\": \"3\"")))
            .isInstanceOf(InvalidConfigException.class);
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"published_by\": \"alice\"", "\"published_by\": 7")))
            .isInstanceOf(InvalidConfigException.class);
        byte[] trailing = (new String(fixture(), StandardCharsets.UTF_8) + " invalid-json").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ConfigCodec.decode(trailing)).isInstanceOf(InvalidConfigException.class);
        assertThat(ConfigCodec.decode(fixture()).version()).isEqualTo(3L); // 原 fixture 仍合法
    }
}
