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

/**
 * Node 間端點認證（D14 修 2）：Authorization: Bearer <token> → sha256 → 查 peer_token_sha256 反查 Node 名。
 * 驗證端只持有雜湊。不用 Spring Security：一個 header、一張表。
 */
@Component
public class NodeAuthFilter extends OncePerRequestFilter {
    static final List<String> PROTECTED_PREFIXES = List.of("/pending", "/file", "/report", "/received");

    private final Map<String, String> nodeByTokenHash;

    public NodeAuthFilter(NodeConfig config) {
        Map<String, String> m = new HashMap<>();
        config.operational().peerTokenSha256().forEach((node, hash) -> m.put(hash, node));
        this.nodeByTokenHash = Map.copyOf(m);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String p : PROTECTED_PREFIXES) {
            if (path.equals(p) || path.startsWith(p + "/")) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        String token = header.substring("Bearer ".length()).strip();
        String node = nodeByTokenHash.get(Sha256.hex(token.getBytes(StandardCharsets.UTF_8)));
        if (node == null) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        req.setAttribute(CallerIdentity.ATTR, node);
        chain.doFilter(req, res);
    }
}
