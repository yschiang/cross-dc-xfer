package com.gigaxfer.core.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gigaxfer.core.identity.FileIdentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 設定檔編解碼與驗證。decode 只回傳通過全部驗證的 NodeConfig；Policy 已正規化（排序）。 */
public final class ConfigCodec {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_NODES = 10;
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    private static final JsonMapper MAPPER = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .build();

    private ConfigCodec() {}

    public static NodeConfig decode(byte[] json) throws InvalidConfigException {
        NodeConfig raw;
        try {
            raw = MAPPER.readValue(json, NodeConfig.class);
        } catch (IOException e) {
            throw new InvalidConfigException("malformed config: " + e.getMessage(), e);
        }
        return validate(raw);
    }

    public static byte[] encode(NodeConfig c) {
        return write(c);
    }

    public static byte[] encodePolicy(Policy p) {
        return write(p.normalized());
    }

    private static byte[] write(Object o) {
        try {
            return (MAPPER.writeValueAsString(o) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static NodeConfig validate(NodeConfig c) throws InvalidConfigException {
        if (c.schemaVersion() != SCHEMA_VERSION) {
            throw new InvalidConfigException("schema_version must be " + SCHEMA_VERSION + ", got " + c.schemaVersion());
        }
        if (c.version() < 1) {
            throw new InvalidConfigException("version must be >= 1, got " + c.version());
        }
        require(c.publishedBy() != null && !c.publishedBy().isBlank(), "published_by is required");
        require(c.publishedAt() != null, "published_at is required");
        require(c.policy() != null, "policy is required");
        require(c.operational() != null, "operational is required");

        Policy p = validatePolicy(c.policy());
        validateOperational(c.operational(), p);

        FixedConstants fixed = c.fixed();
        if (fixed == null) {
            fixed = FixedConstants.V1;
        } else if (!fixed.equals(FixedConstants.V1)) {
            throw new InvalidConfigException("fixed segment must equal v1 constants " + FixedConstants.V1 + ", got " + fixed);
        }
        return new NodeConfig(c.schemaVersion(), c.version(), c.publishedBy(), c.publishedAt(), p, c.operational(), fixed);
    }

    private static Policy validatePolicy(Policy p) throws InvalidConfigException {
        require(p.deployment() != null && !p.deployment().isBlank(), "policy.deployment is required");
        require(p.nodes() != null && !p.nodes().isEmpty(), "policy.nodes must not be empty");
        require(p.nodes().size() <= MAX_NODES, "policy.nodes must have at most " + MAX_NODES + " entries");
        Set<String> nodes = new HashSet<>();
        for (String n : p.nodes()) {
            segment("policy.nodes", n);
            require(nodes.add(n), "policy.nodes has duplicate " + n);
        }
        require(p.namespaces() != null, "policy.namespaces is required");
        Set<String> namespaces = new HashSet<>();
        for (String namespace : p.namespaces()) {
            segment("policy.namespaces", namespace);
            require(namespaces.add(namespace), "policy.namespaces has duplicate " + namespace);
        }
        require(p.requiredTargets() != null, "policy.required_targets is required");
        Set<String> pairs = new HashSet<>();
        for (RequiredTargets rt : p.requiredTargets()) {
            segment("required_targets.source_node", rt.sourceNode());
            segment("required_targets.data_class", rt.dataClass());
            require(nodes.contains(rt.sourceNode()), "required_targets.source_node " + rt.sourceNode() + " not in policy.nodes");
            require(pairs.add(rt.sourceNode() + "\u0000" + rt.dataClass()),
                "required_targets has duplicate (" + rt.sourceNode() + ", " + rt.dataClass() + ")");
            Set<String> seen = new HashSet<>();
            for (String t : rt.targets()) {
                require(nodes.contains(t), "required_targets.targets " + t + " not in policy.nodes");
                require(!t.equals(rt.sourceNode()), "required_targets.targets " + t + " equals its own source_node");
                require(seen.add(t), "required_targets.targets has duplicate " + t);
            }
        }
        return p.normalized();
    }

    private static void validateOperational(OperationalPolicy o, Policy p) throws InvalidConfigException {
        positive("scan_interval_seconds", o.scanIntervalSeconds());
        positive("poll_interval_seconds", o.pollIntervalSeconds());
        positive("pending_limit", o.pendingLimit());
        positive("transfer_timeout_base_seconds", o.transferTimeoutBaseSeconds());
        positive("transfer_timeout_bytes_per_second", o.transferTimeoutBytesPerSecond());
        positive("target_concurrency", o.targetConcurrency());
        positive("target_rate_limit_bytes_per_second", o.targetRateLimitBytesPerSecond());
        positive("file_work_budget", o.fileWorkBudget());
        positive("unreachable_polls", o.unreachablePolls());
        positive("backoff_max_seconds", o.backoffMaxSeconds());
        positive("capacity_reject_bytes", o.capacityRejectBytes());
        positive("capacity_alert_bytes", o.capacityAlertBytes());
        require(o.capacityRejectBytes() < o.capacityAlertBytes(),
            "capacity_reject_bytes must be below capacity_alert_bytes");
        Set<String> tokenHashes = new HashSet<>();
        for (Map.Entry<String, String> e : o.peerTokenSha256().entrySet()) {
            require(p.nodes().contains(e.getKey()), "peer_token_sha256 has unknown node " + e.getKey());
            require(e.getValue() != null && SHA256_HEX.matcher(e.getValue()).matches(),
                "peer_token_sha256[" + e.getKey() + "] must be 64 lowercase hex (sha256)");
            require(tokenHashes.add(e.getValue()), "peer_token_sha256 must uniquely identify each node");
        }
    }

    private static void segment(String field, String value) throws InvalidConfigException {
        try {
            FileIdentity.requireSegment(value, field);
        } catch (IllegalArgumentException e) {
            throw new InvalidConfigException(e.getMessage(), e);
        }
    }

    private static void positive(String field, long v) throws InvalidConfigException {
        require(v > 0, field + " must be > 0, got " + v);
    }

    private static void require(boolean ok, String reason) throws InvalidConfigException {
        if (!ok) {
            throw new InvalidConfigException(reason);
        }
    }
}
