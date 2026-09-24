package com.gigaxfer.core.layout;

import com.gigaxfer.core.digest.Sha256;
import com.gigaxfer.core.identity.FileIdentity;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * NFS 佈局（D2 修、D48、system-design §3「檔案」）。
 * 內容：<root>/<source>/<ns>/<class>/<yyyy-MM-dd>/<HH>/<key>
 * manifest：<root>/<source>/<ns>/.manifest/<bucket>/<key>.manifest，bucket 只由 key 決定。
 */
public final class PathLayout {
    public static final String MANIFEST_DIR = ".manifest";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH");

    private final Path root;
    private final ZoneId zone;

    public PathLayout(Path root, ZoneId zone) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public Path root() {
        return root;
    }

    public Path contentDir(FileIdentity id, String dataClass, Instant at) {
        ZonedDateTime t = at.atZone(zone);
        return root.resolve(id.sourceNode()).resolve(id.namespace()).resolve(dataClass)
            .resolve(DAY.format(t)).resolve(HOUR.format(t));
    }

    /** 由 manifest 的 identity、Data class 與 Source Ready 時刻唯一推導出的相對正式路徑。 */
    public String expectedContentPath(FileIdentity id, String dataClass, Instant sourceReadyAt) {
        return toContentPath(contentDir(id, dataClass, sourceReadyAt).resolve(id.logicalKey()));
    }

    public Path manifestDir(FileIdentity id) {
        return root.resolve(id.sourceNode()).resolve(id.namespace()).resolve(MANIFEST_DIR).resolve(bucket(id.logicalKey()));
    }

    public Path manifestPath(FileIdentity id) {
        return manifestDir(id).resolve(id.logicalKey() + ".manifest");
    }

    public Path manifestTmpPath(FileIdentity id, UUID uuid) {
        return manifestDir(id).resolve(id.logicalKey() + ".manifest." + uuid + ".tmp");
    }

    public Path writingPath(Path dir, FileIdentity id, UUID uuid) {
        return dir.resolve(id.logicalKey() + "." + uuid + ".writing");
    }

    /** manifest.content_path：相對 root、'/' 分隔，Target 鏡像同一字串（D48）。 */
    public String toContentPath(Path absolute) {
        return root.relativize(absolute.toAbsolutePath().normalize()).toString().replace(File.separatorChar, '/');
    }

    public Path fromContentPath(String contentPath) {
        Path p = root.resolve(contentPath).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("content_path escapes root: " + contentPath);
        return p;
    }

    public static String bucket(String logicalKey) {
        byte[] d = Sha256.newDigest().digest(logicalKey.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(d).substring(0, 3);
    }
}
