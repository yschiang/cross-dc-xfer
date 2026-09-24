package com.gigaxfer.core.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.Map;

/** operational policy 可調項（D30、D30 修、D30 修 3、D52）；peer_token_sha256 = 各 Node token 的 sha256 hex（D14 修 2）。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OperationalPolicy(
    int scanIntervalSeconds,
    int pollIntervalSeconds,
    int pendingLimit,
    int transferTimeoutBaseSeconds,
    long transferTimeoutBytesPerSecond,
    int targetConcurrency,
    long targetRateLimitBytesPerSecond,
    int fileWorkBudget,
    int unreachablePolls,
    int backoffMaxSeconds,
    long capacityRejectBytes,
    long capacityAlertBytes,
    Map<String, String> peerTokenSha256) {
    public OperationalPolicy {
        peerTokenSha256 = Map.copyOf(peerTokenSha256);
    }
}
