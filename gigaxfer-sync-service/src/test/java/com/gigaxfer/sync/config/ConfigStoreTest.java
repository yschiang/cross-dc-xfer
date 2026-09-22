package com.gigaxfer.sync.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gigaxfer.core.config.ConfigCodec;
import com.gigaxfer.core.config.NodeConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreTest {
    @TempDir Path dir;

    private static String fixture() throws IOException {
        try (InputStream in = ConfigStoreTest.class.getResourceAsStream("/config/v3.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String withVersion(String json, long v) {
        return json.replace("\"version\": 3", "\"version\": " + v);
    }

    private void put(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }

    @Test
    void refuses_when_neither_active_nor_lkg_exists() {
        assertThatThrownBy(() -> new ConfigStore(dir).load())
            .isInstanceOf(ConfigUnavailableException.class);
    }

    @Test
    void loads_active_when_no_candidate() throws Exception {
        put("active.json", fixture());
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.source()).isEqualTo(ConfigActivation.Source.ACTIVE);
        assertThat(a.config().version()).isEqualTo(3L);
        assertThat(a.activationFailure()).isEmpty();
        assertThat(dir.resolve("lkg.json")).doesNotExist();
    }

    @Test
    void falls_back_to_lkg_when_active_missing() throws Exception {
        put("lkg.json", withVersion(fixture(), 2));
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.source()).isEqualTo(ConfigActivation.Source.LKG);
        assertThat(a.config().version()).isEqualTo(2L);
    }

    @Test
    void falls_back_to_lkg_when_active_is_corrupt() throws Exception {
        put("active.json", "{ not json");
        put("lkg.json", withVersion(fixture(), 2));
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.source()).isEqualTo(ConfigActivation.Source.LKG);
        assertThat(a.activationFailure()).isPresent();
    }

    @Test
    void activates_valid_candidate_and_rotates_active_to_lkg() throws Exception {
        put("active.json", withVersion(fixture(), 3));
        put("candidate.json", withVersion(fixture(), 4));
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.source()).isEqualTo(ConfigActivation.Source.ACTIVE);
        assertThat(a.config().version()).isEqualTo(4L);
        assertThat(a.activationFailure()).isEmpty();
        assertThat(dir.resolve("candidate.json")).doesNotExist();
        assertThat(ConfigCodec.decode(Files.readAllBytes(dir.resolve("active.json"))).version()).isEqualTo(4L);
        assertThat(ConfigCodec.decode(Files.readAllBytes(dir.resolve("lkg.json"))).version()).isEqualTo(3L);
    }

    @Test
    void rejects_candidate_with_non_increasing_version_and_keeps_it() throws Exception {
        put("active.json", withVersion(fixture(), 3));
        put("candidate.json", withVersion(fixture(), 3));
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.config().version()).isEqualTo(3L);
        assertThat(a.activationFailure()).isPresent().get().asString().contains("version");
        assertThat(dir.resolve("candidate.json")).exists();
        assertThat(dir.resolve("lkg.json")).doesNotExist();
    }

    @Test
    void rejects_candidate_whose_policy_differs() throws Exception {
        put("active.json", withVersion(fixture(), 3));
        put("candidate.json", withVersion(fixture(), 4).replace("\"targets\": [\"P1\"]", "\"targets\": [\"P1\", \"P3\"]"));
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.config().version()).isEqualTo(3L);
        assertThat(a.activationFailure()).isPresent().get().asString().contains("policy");
        assertThat(dir.resolve("candidate.json")).exists();
    }

    @Test
    void accepts_candidate_that_only_changes_operational_and_node_order() throws Exception {
        put("active.json", withVersion(fixture(), 3));
        put("candidate.json", withVersion(fixture(), 4)
            .replace("\"pending_limit\": 200", "\"pending_limit\": 100")
            .replace("[\"P1\", \"P2\", \"P3\"]", "[\"P3\", \"P2\", \"P1\"]"));
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.config().version()).isEqualTo(4L);
        assertThat(a.config().operational().pendingLimit()).isEqualTo(100);
    }

    @Test
    void rejects_malformed_candidate_and_keeps_active() throws Exception {
        put("active.json", withVersion(fixture(), 3));
        put("candidate.json", "{ \"schema_version\": 1 ");
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.config().version()).isEqualTo(3L);
        assertThat(a.activationFailure()).isPresent();
        assertThat(dir.resolve("candidate.json")).exists();
    }

    @Test
    void crash_between_renames_recovers_on_next_start() throws Exception {
        // 模擬：active→lkg 已完成、candidate→active 尚未完成時 crash。
        put("lkg.json", withVersion(fixture(), 3));
        put("candidate.json", withVersion(fixture(), 4));
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.source()).isEqualTo(ConfigActivation.Source.ACTIVE);
        assertThat(a.config().version()).isEqualTo(4L);
        assertThat(dir.resolve("candidate.json")).doesNotExist();
        assertThat(ConfigCodec.decode(Files.readAllBytes(dir.resolve("lkg.json"))).version()).isEqualTo(3L);
    }

    @Test
    void candidate_is_validated_against_lkg_when_active_missing() throws Exception {
        put("lkg.json", withVersion(fixture(), 3));
        put("candidate.json", withVersion(fixture(), 4).replace("\"targets\": [\"P1\"]", "\"targets\": [\"P1\", \"P3\"]"));
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.source()).isEqualTo(ConfigActivation.Source.LKG);
        assertThat(a.config().version()).isEqualTo(3L);
        assertThat(a.activationFailure()).isPresent().get().asString().contains("policy");
        assertThat(dir.resolve("candidate.json")).exists();
    }

    @Test
    void first_initialisation_accepts_candidate_when_nothing_else_exists() throws Exception {
        put("candidate.json", withVersion(fixture(), 1));
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.source()).isEqualTo(ConfigActivation.Source.ACTIVE);
        assertThat(a.config().version()).isEqualTo(1L);
        assertThat(dir.resolve("active.json")).exists();
        assertThat(dir.resolve("candidate.json")).doesNotExist();
    }

    @Test
    void ignores_candidate_tmp_still_being_written_by_cd() throws Exception {
        put("active.json", fixture());
        put("candidate.json.tmp", "partial");
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.config().version()).isEqualTo(3L);
        assertThat(a.activationFailure()).isEmpty();
    }
}
