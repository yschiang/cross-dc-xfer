package com.gigaxfer.sync.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 由 NodeAuthFilter 放入 request attribute 的呼叫者 Node 名。 */
public final class CallerIdentity {
    static final String ATTR = "gigaxfer.caller";

    private CallerIdentity() {}

    public static String of(HttpServletRequest req) {
        Object v = req.getAttribute(ATTR);
        if (v == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no caller identity");
        }
        return (String) v;
    }

    /** `target` 由身分取得；請求另帶且不同 → 403（D14 修 2）。 */
    public static void requireTarget(HttpServletRequest req, String targetParam) {
        String caller = of(req);
        if (targetParam != null && !targetParam.equals(caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "target " + targetParam + " is not the caller " + caller);
        }
    }
}
