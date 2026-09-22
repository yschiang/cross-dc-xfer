package com.gigaxfer.sync.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.gigaxfer.core.config.ConfigCodec;
import com.gigaxfer.core.config.NodeConfig;
import com.gigaxfer.sync.SyncTestSupport;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@link NodeAuthFilter#shouldNotFilter} 直接單元測試（不經 MockMvc / servlet 容器）：驗證路徑比對用
 * 解碼後、去掉 {@code ;} 參數的路徑，而不是原始 {@code getRequestURI()}（審查 H-1 / M-2）。
 */
class NodeAuthFilterUnitTest {
    private final NodeAuthFilter filter = newFilter();

    private static NodeAuthFilter newFilter() {
        try {
            NodeConfig config = ConfigCodec.decode(SyncTestSupport.fixture().getBytes(StandardCharsets.UTF_8));
            return new NodeAuthFilter(config);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/pending;x=1", "/%70ending", "/pendingx", "/file/P1/mes/k1", "/anything-new"})
    void protected_paths_require_auth_even_when_obfuscated(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        assertThat(filter.shouldNotFilter(req)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/policy", "/actuator/health/liveness", "/locate/x", "/error"})
    void exempt_paths_need_no_token(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        assertThat(filter.shouldNotFilter(req)).isTrue();
    }

    @Test
    void dot_segments_under_exempt_prefix_fall_back_to_auth() {
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/../pending"))).isFalse();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/policy/./x"))).isFalse();
    }
}
