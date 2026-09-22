package com.gigaxfer.core.digest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 內容完整性基準的唯一演算法（IR-01）。永不整檔進記憶體。 */
public final class Sha256 {
    public static final String PREFIX = "sha256:";
    private static final int BUFFER = 64 * 1024;

    private Sha256() {}

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
    }

    /** 注意：MessageDigest.digest() 會重設狀態，每個 digest 物件只呼叫一次。 */
    public static String format(MessageDigest md) {
        return PREFIX + HexFormat.of().formatHex(md.digest());
    }

    public static String ofBytes(byte[] bytes) {
        MessageDigest md = newDigest();
        md.update(bytes);
        return format(md);
    }

    public static String ofFile(Path path) throws IOException {
        MessageDigest md = newDigest();
        byte[] buf = new byte[BUFFER];
        try (InputStream in = Files.newInputStream(path)) {
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return format(md);
    }
}
