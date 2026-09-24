package com.gigaxfer.sync.config;

import com.gigaxfer.core.config.ConfigCodec;
import com.gigaxfer.core.config.NodeConfig;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Node 內端點：library 取 Policy 段（D18）。不認證（同主機群、Node 內）。 */
@RestController
public class PolicyController {
    private final byte[] body;

    public PolicyController(NodeConfig config) {
        String policy = new String(ConfigCodec.encodePolicy(config.policy()), StandardCharsets.UTF_8).strip();
        this.body = ("{\"version\":" + config.version() + ",\"policy\":" + policy + "}\n").getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping(value = "/policy", produces = MediaType.APPLICATION_JSON_VALUE)
    public byte[] policy() {
        return body;
    }
}
