package com.gigaxfer.sync.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 測試專用：站在 /pending、/received 位置回報 filter 辨識出的呼叫者。正式端點由 P04 提供。
 * Echo 不另外開 @Bean 工廠方法：@Import 一個 @TestConfiguration 類別時，Spring 會透過
 * processMemberClasses 自動註冊其巢狀 @Component 類別；額外的 @Bean 方法會造成同一個
 * handler method 註冊兩次（ambiguous mapping）。
 */
@TestConfiguration
public class EchoCallerController {
    @RestController
    public static class Echo {
        @GetMapping("/pending")
        public Map<String, String> pending(
                HttpServletRequest req, @RequestParam(name = "target", required = false) String target) {
            CallerIdentity.requireTarget(req, target);
            return Map.of("caller", CallerIdentity.of(req));
        }

        /** 只列出 caller 為 Source 的資料，不套用 target==caller 規則（Task 6 補充）。 */
        @GetMapping("/received")
        public Map<String, String> received(HttpServletRequest req) {
            return Map.of("caller", CallerIdentity.of(req));
        }
    }
}
