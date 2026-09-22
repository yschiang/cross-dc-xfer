package com.gigaxfer.core.config;

import java.io.IOException;

/** 設定驗證失敗；extends IOException 以沿用 gigaxfer-core 既有風格（見 MalformedManifestException），
 * 使呼叫端測試可用單一 {@code throws IOException} 涵蓋。 */
public final class InvalidConfigException extends IOException {
    public InvalidConfigException(String reason) {
        super(reason);
    }

    public InvalidConfigException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
