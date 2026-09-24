package com.gigaxfer.sync.auth;

import com.gigaxfer.core.config.NodeConfig;
import com.gigaxfer.core.digest.Sha256;
import com.gigaxfer.sync.SyncProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 本 Node 的明文 token：啟動時自本機秘密檔讀一次（system-design §3「檔案」）。供後續對他 Node 的呼叫使用。 */
@Component
public class OwnToken {
    private static final Logger log = LoggerFactory.getLogger(OwnToken.class);
    private final String value;

    public OwnToken(SyncProperties props, NodeConfig config) throws IOException {
        if (props.tokenFile() == null) {
            throw new IllegalStateException("gigaxfer.token-file is not set");
        }
        this.value = Files.readString(props.tokenFile(), StandardCharsets.UTF_8).strip();
        if (value.isEmpty()) {
            throw new IllegalStateException("token file " + props.tokenFile() + " is empty");
        }
        String expected = config.operational().peerTokenSha256().get(props.node());
        if (expected == null || !expected.equals(Sha256.hex(value.getBytes(StandardCharsets.UTF_8)))) {
            // 輪替窗口內允許不一致：其他 Node 會拒絕本 Node，直到 config 更新。只警告不阻擋（D14 修 2、D30）。
            log.warn("sha256 of own token does not match peer_token_sha256[{}] in active config; peers will reject calls from this node", props.node());
        }
    }

    public String value() {
        return value;
    }
}
