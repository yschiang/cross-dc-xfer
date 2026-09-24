package com.gigaxfer.sync.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 由 NodeAuthFilter 放入 request attribute 的呼叫者 Node 名。 */
public final class CallerIdentity {
    static final String ATTR = "gigaxfer.caller";
    private static final String TARGET_MISMATCH = "target does not match caller";
    private static final Logger log = LoggerFactory.getLogger(CallerIdentity.class);

    private CallerIdentity() {}

    public static String of(HttpServletRequest req) {
        Object v = req.getAttribute(ATTR);
        if (v == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no caller identity");
        }
        return (String) v;
    }

    /** `target` 由身分取得；請求另帶且不同 → 403（D14 修 2）。回應訊息不帶細節，避免洩漏內部身分；細節寫 debug log。 */
    public static void requireTarget(HttpServletRequest req, String targetParam) {
        String caller = of(req);
        if (targetParam != null && !targetParam.equals(caller)) {
            log.debug("target {} does not match caller {}", targetParam, caller);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, TARGET_MISMATCH);
        }
    }
}
