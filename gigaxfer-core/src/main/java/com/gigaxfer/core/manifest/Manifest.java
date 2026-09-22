package com.gigaxfer.core.manifest;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.gigaxfer.core.identity.FileIdentity;

import java.time.Instant;

/** Source Ready 的權威紀錄（D1、D48）。欄位順序即 JSON 順序。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Manifest(
    int schemaVersion,
    String sourceNode,
    String namespace,
    String dataClass,
    String logicalKey,
    long size,
    String digest,
    String uuid,
    Instant sourceReadyAt,
    String contentPath
) {
    public static final int SCHEMA_VERSION = 1;

    public FileIdentity identity() {
        return new FileIdentity(sourceNode, namespace, logicalKey);
    }

    /** D53 修 ①：identity、Data class、size、digest 四項皆同才算同一宣告。 */
    public boolean sameDeclaration(FileIdentity id, String dataClass, long size, String digest) {
        return identity().equals(id) && this.dataClass.equals(dataClass) && this.size == size && this.digest.equals(digest);
    }
}
