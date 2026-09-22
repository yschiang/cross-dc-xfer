package com.gigaxfer.core.manifest;

import java.io.IOException;

/** 半截或不合法的 manifest；依 D44 只可能出現在 tmp，正式 manifest 必為完整。 */
public final class MalformedManifestException extends IOException {
    public MalformedManifestException(String message, Throwable cause) {
        super(message, cause);
    }

    public MalformedManifestException(String message) {
        super(message);
    }
}
