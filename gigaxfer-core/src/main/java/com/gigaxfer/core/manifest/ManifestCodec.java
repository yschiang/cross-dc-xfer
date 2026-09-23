package com.gigaxfer.core.manifest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gigaxfer.core.identity.FileIdentity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Pattern;

public final class ManifestCodec {
    /**
     * 單行 manifest 的上限。實際約 300 B + 各路徑片段；即使每段都接近 NAME_MAX 255 B 也不到 4 KB。
     * 超過就是損壞宣告，讀取時也不會為它配置超過此大小的記憶體。
     */
    public static final int MAX_BYTES = 16 * 1024;
    private static final Pattern DIGEST_PATTERN = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern DAY = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
    private static final Pattern HOUR = Pattern.compile("^([01][0-9]|2[0-3])$");

    private final ObjectMapper mapper = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        // 固定 schema：record 的每個欄位都必填。沒有這三項時 Jackson 對缺席或 null 的 primitive
        // 補 0，並把 "12"、1.5 強制轉成 long——缺 size 的損壞宣告會被當成 size=0 的有效宣告（P01-03）。
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .build();

    public byte[] encode(Manifest m) {
        try {
            return (mapper.writeValueAsString(m) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unencodable manifest", e);
        }
    }

    /** 有界讀取：最多讀 MAX_BYTES + 1 位元組（readNBytes 分塊配置，不依檔案大小預先配置）。 */
    public Manifest read(InputStream in) throws IOException {
        return decode(in.readNBytes(MAX_BYTES + 1));
    }

    public Manifest decode(byte[] bytes) throws MalformedManifestException {
        if (bytes.length > MAX_BYTES) throw new MalformedManifestException("manifest larger than " + MAX_BYTES + " bytes");
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
        try {
            new FileIdentity(m.sourceNode(), m.namespace(), m.logicalKey());
            FileIdentity.requireSegment(m.dataClass(), "data_class", FileIdentity.MAX_DATA_CLASS_BYTES);
        } catch (IllegalArgumentException e) {
            throw new MalformedManifestException("source_node/namespace/data_class/logical_key invalid: " + e.getMessage(), e);
        }
        requireDerivedContentPath(m);
        return m;
    }

    /**
     * P01-03：content_path 必須是 &lt;source&gt;/&lt;ns&gt;/&lt;class&gt;/&lt;yyyy-MM-dd&gt;/&lt;HH&gt;/&lt;key&gt;，
     * 除日／小時目錄外每段都由本宣告的 identity 與 data class 決定。否則發布會被導向別的 identity 的位置。
     *
     * <p>codec 只驗日／小時「是合法的日期與小時」，位置歸屬（不能指向別的 identity）由其餘四段保證；
     * 與 source_ready_at 的時區比對在 WriteHandle.readManifest 以 PathLayout.expectedContentPath 完成。
     */
    private static void requireDerivedContentPath(Manifest m) throws MalformedManifestException {
        String[] seg = m.contentPath().split("/", -1);
        boolean ok = seg.length == 6
            && seg[0].equals(m.sourceNode()) && seg[1].equals(m.namespace()) && seg[2].equals(m.dataClass())
            && DAY.matcher(seg[3]).matches() && HOUR.matcher(seg[4]).matches()
            && seg[5].equals(m.logicalKey());
        if (ok) {
            try {
                LocalDate.parse(seg[3]); // ISO_LOCAL_DATE 是 STRICT：2026-02-30 會被拒
            } catch (DateTimeException e) {
                ok = false;
            }
        }
        if (!ok) throw new MalformedManifestException("content_path not derived from identity and data_class: " + m.contentPath());
    }
}
