package com.gigaxfer.sync.config;

/** active.json 與 lkg.json 皆缺或皆無法解碼：拒絕啟動（D17）。 */
public final class ConfigUnavailableException extends Exception {
    public ConfigUnavailableException(String message) {
        super(message);
    }
}
