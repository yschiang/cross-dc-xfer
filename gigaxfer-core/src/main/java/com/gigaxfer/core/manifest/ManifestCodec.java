package com.gigaxfer.core.manifest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ManifestCodec {
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
        return m;
    }
}
