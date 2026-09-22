package com.gigaxfer.core.digest;

import com.gigaxfer.core.nfs.NfsBusyException;
import com.gigaxfer.core.nfs.NfsExecutor;
import com.gigaxfer.core.nfs.NfsTimeoutException;

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

    /**
     * 唯一的檔案 digest 入口（P01-07：NFS 操作一律經有界執行器，刻意不提供繞過執行器的 overload）。
     * 經有界執行器逐塊讀：開檔、每個 64 KB read、關檔各自一次 op，
     * 不讓「整檔讀取」卡成一個超過 executor timeout 的巨大操作（大檔會永遠拿不到結果、
     * 每次重試又重讀整檔並占滿槽位）。任一塊 timeout／池滿時 NfsException 原樣往外傳。
     */
    public static String ofFile(NfsExecutor nfs, String op, Path path)
            throws NfsBusyException, NfsTimeoutException, IOException {
        MessageDigest md = newDigest();
        byte[] buf = new byte[BUFFER];
        InputStream in = nfs.call(op, () -> Files.newInputStream(path));
        try {
            int n;
            while ((n = nfs.call(op, () -> in.read(buf))) != -1) md.update(buf, 0, n);
        } finally {
            try {
                nfs.run(op, in::close); // best-effort：耐久性與正確性都不靠 close
            } catch (Exception ignored) {
                // 清道夫兜底（D35）
            }
        }
        return format(md);
    }
}
