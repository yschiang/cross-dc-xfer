package com.gigaxfer.sync.auth;

import com.gigaxfer.core.config.NodeConfig;
import com.gigaxfer.core.digest.Sha256;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Node 間端點認證（D14 修 2）：Authorization: Bearer <token> → sha256 → 查 peer_token_sha256 反查 Node 名。
 * 驗證端只持有雜湊。不用 Spring Security：一個 header、一張表。
 *
 * <p>Fail-closed（審查 Ruling O）：只有明列在 {@link #EXEMPT} 的 Node 內部端點不需認證，其餘路徑一律需要
 * token；新增端點不需要記得把它加進「受保護清單」才安全。路徑比對用 {@link UrlPathHelper#getPathWithinApplication}
 * 而非 {@code request.getRequestURI()}：後者未解碼、保留 {@code ;} 路徑參數，
 * {@code /%70ending}、{@code /pending;x=1} 這類請求會讓字串比對誤判為「不受保護」而放行到未認證的 handler。
 */
@Component
public class NodeAuthFilter extends OncePerRequestFilter {
    static final List<String> EXEMPT = List.of("/policy", "/locate", "/actuator", "/error");

    private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;

    private final Map<String, String> nodeByTokenHash;

    public NodeAuthFilter(NodeConfig config) {
        Map<String, String> m = new HashMap<>();
        config.operational().peerTokenSha256().forEach((node, hash) -> m.put(hash, node));
        this.nodeByTokenHash = Map.copyOf(m);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = PATH_HELPER.getPathWithinApplication(request);
        if (path.contains("/../") || path.contains("/./") || path.endsWith("/..") || path.endsWith("/.")) {
            return false; // 不自行解 dot-segment，落回需認證（fail-closed）
        }
        for (String p : EXEMPT) {
            if (path.equals(p) || path.startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            unauthorized(res);
            return;
        }
        String token = header.substring(7).strip();
        String node = nodeByTokenHash.get(Sha256.hex(token.getBytes(StandardCharsets.UTF_8)));
        if (node == null) {
            unauthorized(res);
            return;
        }
        req.setAttribute(CallerIdentity.ATTR, node);
        chain.doFilter(req, res);
    }

    private static void unauthorized(HttpServletResponse res) throws IOException {
        res.setHeader("WWW-Authenticate", "Bearer");
        res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
