package com.gigaxfer.core.identity;

/**
 * File identity = (Source Node, Namespace, Logical key)（CONTEXT.md）。
 * 三段直接成為路徑片段；Logical key 另有保留字尾（file-inventory 的 .writing / .tmp / .manifest）。
 */
public record FileIdentity(String sourceNode, String namespace, String logicalKey) {

    public FileIdentity {
        requireSegment(sourceNode, "sourceNode");
        requireSegment(namespace, "namespace");
        requireSegment(logicalKey, "logicalKey");
        if (logicalKey.endsWith(".writing") || logicalKey.endsWith(".tmp") || logicalKey.contains(".manifest")) {
            throw new IllegalArgumentException("logicalKey uses reserved suffix: " + logicalKey);
        }
    }

    /** 可重用的路徑片段檢查：Data class 等「不是 identity 但同樣直接成為路徑片段」的值也要過這關。 */
    public static void requireSegment(String v, String name) {
        if (v == null || v.isEmpty() || v.startsWith(".") || v.indexOf('/') >= 0 || v.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " is not a valid path segment: " + v);
        }
    }
}
