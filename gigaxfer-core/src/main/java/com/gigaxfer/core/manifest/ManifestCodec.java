package com.gigaxfer.core.manifest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gigaxfer.core.identity.FileIdentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public final class ManifestCodec {
    private static final Pattern DIGEST_PATTERN = Pattern.compile("^sha256:[0-9a-f]{64}$");

    private final ObjectMapper mapper = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    public byte[] encode(Manifest m) {
        try {
            return (mapper.writeValueAsString(m) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unencodable manifest", e);
        }
    }

    public Manifest decode(byte[] bytes) throws MalformedManifestException {
        Manifest m;
        try {
            m = mapper.readValue(bytes, Manifest.class);
        } catch (IOException e) {
            throw new MalformedManifestException("manifest parse failed: " + e.getMessage(), e);
        }
        if (m.schemaVersion() != Manifest.SCHEMA_VERSION) throw new MalformedManifestException("schema_version " + m.schemaVersion());
        if (m.sourceNode() == null || m.namespace() == null || m.dataClass() == null || m.logicalKey() == null
            || m.digest() == null || m.uuid() == null || m.sourceReadyAt() == null || m.contentPath() == null) {
            throw new MalformedManifestException("manifest missing required field");
        }
        if (!DIGEST_PATTERN.matcher(m.digest()).matches()) {
            throw new MalformedManifestException("digest is not sha256:<64 lowercase hex>: " + m.digest());
        }
        if (m.size() < 0) {
            throw new MalformedManifestException("size is negative: " + m.size());
        }
        if (m.dataClass().isEmpty()) {
            throw new MalformedManifestException("data_class is empty");
        }
        if (m.contentPath().isEmpty()) {
            throw new MalformedManifestException("content_path is empty");
        }
        try {
            new FileIdentity(m.sourceNode(), m.namespace(), m.logicalKey());
        } catch (IllegalArgumentException e) {
            throw new MalformedManifestException("source_node/namespace/logical_key invalid: " + e.getMessage(), e);
        }
        return m;
    }
}
