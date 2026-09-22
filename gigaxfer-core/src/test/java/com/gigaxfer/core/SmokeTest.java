package com.gigaxfer.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmokeTest {
    @Test
    void junit_and_assertj_wired() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }
}
