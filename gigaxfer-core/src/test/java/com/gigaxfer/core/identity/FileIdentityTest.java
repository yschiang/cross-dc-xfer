package com.gigaxfer.core.identity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileIdentityTest {
    @Test
    void accepts_plain_segments() {
        FileIdentity id = new FileIdentity("P3", "mes", "L123-R2.dat");
        assertThat(id.logicalKey()).isEqualTo("L123-R2.dat");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".hidden", "a/b", "a\0b"})
    void rejects_bad_segment_in_any_position(String bad) {
        assertThatThrownBy(() -> new FileIdentity(bad, "mes", "k")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FileIdentity("P3", bad, "k")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FileIdentity("P3", "mes", bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"L1.writing", "L1.tmp", "L1.manifest", "L1.manifest.x"})
    void rejects_reserved_suffix_in_logical_key(String bad) {
        assertThatThrownBy(() -> new FileIdentity("P3", "mes", bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void null_segment_rejected() {
        assertThatThrownBy(() -> new FileIdentity(null, "mes", "k")).isInstanceOf(IllegalArgumentException.class);
    }
}
