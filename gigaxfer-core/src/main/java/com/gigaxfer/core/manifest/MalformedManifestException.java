package com.gigaxfer.core.manifest;

import java.io.IOException;

/** 半截或不合法的 manifest。依 D44 半截只會出現在 tmp；正式 manifest 也可能因 schema／content_path 檢查不過而判為不合法，呼叫端視為該宣告不可用。 */
public final class MalformedManifestException extends IOException {
    public MalformedManifestException(String message, Throwable cause) {
        super(message, cause);
    }

    public MalformedManifestException(String message) {
        super(message);
    }
}
