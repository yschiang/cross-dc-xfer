package com.gigaxfer.core.identity;

/**
 * File identity = (Source Node, Namespace, Logical key)（CONTEXT.md）。
 * 三段直接成為路徑片段；Logical key 另有保留字尾（system-design §3「檔案」的 .writing / .tmp / .manifest）。
 */
public record FileIdentity(String sourceNode, String namespace, String logicalKey) {
    /**
     * 各片段的 UTF-8 位元組上限＝sync-service V1 schema 的欄寬（Oracle VARCHAR 預設 BYTE 語意）。
     * 在 beginWrite／config／manifest 入口就拒絕，NAS 上不會出現 DB 寫不進去的 Source Ready 檔。
     * content_path = node/namespace/class/yyyy-mm-dd/HH/key ≤ 64+128+128+10+2+512+5 = 849 &lt; 1024，不需另檢。
     */
    public static final int MAX_NODE_BYTES = 64;
    public static final int MAX_NAMESPACE_BYTES = 128;
    public static final int MAX_DATA_CLASS_BYTES = 128;
    public static final int MAX_LOGICAL_KEY_BYTES = 512;

    public FileIdentity {
        requireSegment(sourceNode, "sourceNode", MAX_NODE_BYTES);
        requireSegment(namespace, "namespace", MAX_NAMESPACE_BYTES);
        requireSegment(logicalKey, "logicalKey", MAX_LOGICAL_KEY_BYTES);
        if (logicalKey.endsWith(".writing") || logicalKey.endsWith(".tmp") || logicalKey.contains(".manifest")) {
            throw new IllegalArgumentException("logicalKey uses reserved suffix: " + logicalKey);
        }
    }

    /** 可重用的路徑片段檢查：Data class 等「不是 identity 但同樣直接成為路徑片段」的值也要過這關。 */
    public static void requireSegment(String v, String name, int maxBytes) {
        if (v == null || v.isEmpty() || v.startsWith(".") || v.indexOf('/') >= 0 || v.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " is not a valid path segment: " + v);
        }
        int bytes = v.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > maxBytes) {
            throw new IllegalArgumentException(name + " exceeds " + maxBytes + " UTF-8 bytes (" + bytes + ")");
        }
    }
}
