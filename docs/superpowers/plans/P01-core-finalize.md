# P01 — gigaxfer-core Finalize 協議 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付 `gigaxfer-core` 模組：Application 端 library 的 beginWrite / write / finalize / discard 完整協議，在本機檔案系統上以測試證明 F1–F5b、F18（library 側）全部封閉。

**Architecture:** 純 Java 21 模組，無 Spring、無 DB。NFS 是唯一真相：`<key>.manifest` 以 tmp + link 原子宣告，`<key>` 以跨目錄 link 原子發布（commit point）。每個檔案系統操作經有界執行器（固定槽、無佇列、timeout 不釋放槽）。Finalize 冪等：同一 handle 重呼走同一序列，每步以 EEXIST / 存在性 / digest 判定。

**Tech Stack:** Java 21、Maven 多模組、Jackson 2.x（含 jsr310）、JUnit 5、AssertJ。

**Spec:** `docs/design/system-design.md` §2.2、§5、§6（F1–F5b、F18）；`docs/design/design-decisions.md` D1–D4、D44、D48 修、D51、D51 修 2、D53 修、D56 ④；`docs/design/file-inventory.md`；`CONTEXT.md`。

## Global Constraints

- Java 21；`gigaxfer-core` 不得依賴 Spring、任何 DB driver。
- 每個檔案系統操作必須經 `NfsExecutor.call/run`；Application thread 不直接呼叫 `java.nio.file.Files`（D51）。
- 永不整檔進記憶體：digest 以串流計算，讀檔用 64 KB buffer（D4、§2.4 硬規則）。
- digest 格式：`sha256:` + 64 個小寫 hex。
- manifest：單行 JSON + `\n`，snake_case 欄位，`schema_version` = 1，欄位固定為 `schema_version, source_node, namespace, data_class, logical_key, size, digest, uuid, source_ready_at, content_path`（§2.2）。
- 路徑：內容 `<root>/<source>/<ns>/<class>/<yyyy-MM-dd>/<HH>/<key>`；manifest `<root>/<source>/<ns>/.manifest/<bucket>/<key>.manifest`，bucket = sha256(key) 前 3 hex（D48；`.manifest` 目錄名為本計畫決定，見末尾「設計偏差」）；暫存 `<dir>/<key>.<uuid>.writing`、`<bucket>/<key>.manifest.<uuid>.tmp`（file-inventory）。
- `content_path` 存相對 root、`/` 分隔的字串，兩端 Node 鏡像（D48）。
- 宣告年齡上限 N = 7 天為 v1 固定常數，不從設定讀（D30 修 5、D53 修）。
- Logical key 保留字尾：不得以 `.writing`、`.tmp` 結尾，不得含 `.manifest`；三段皆不得含 `/`、NUL，不得以 `.` 開頭（SR-01 Application 義務）。
- 每個 task 以 `mvn -q -pl gigaxfer-core test` 綠燈結束並 commit。

---

### Task 1: Maven 多模組骨架

**Files:**
- Create: `pom.xml`
- Create: `gigaxfer-core/pom.xml`
- Create: `.gitignore`（追加）
- Test: `gigaxfer-core/src/test/java/com/gigaxfer/core/SmokeTest.java`

**Interfaces:**
- Produces: 模組 `com.gigaxfer:gigaxfer-core:0.1.0-SNAPSHOT`，後續所有 task 在此模組內。

- [ ] **Step 1: 寫 parent pom**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.gigaxfer</groupId>
  <artifactId>gigaxfer-parent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <modules>
    <module>gigaxfer-core</module>
  </modules>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <jackson.version>2.18.3</jackson.version>
    <junit.version>5.11.4</junit.version>
    <assertj.version>3.27.3</assertj.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>com.fasterxml.jackson</groupId>
        <artifactId>jackson-bom</artifactId>
        <version>${jackson.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.junit</groupId>
        <artifactId>junit-bom</artifactId>
        <version>${junit.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <version>${assertj.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-compiler-plugin</artifactId>
          <version>3.13.0</version>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-surefire-plugin</artifactId>
          <version>3.5.2</version>
        </plugin>
      </plugins>
    </pluginManagement>
  </build>
</project>
```

- [ ] **Step 2: 寫 core 模組 pom**

`gigaxfer-core/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.gigaxfer</groupId>
    <artifactId>gigaxfer-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>gigaxfer-core</artifactId>

  <dependencies>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.datatype</groupId>
      <artifactId>jackson-datatype-jsr310</artifactId>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

- [ ] **Step 3: .gitignore 追加**

```
target/
*.iml
.idea/
```

- [ ] **Step 4: 寫 smoke test**

`gigaxfer-core/src/test/java/com/gigaxfer/core/SmokeTest.java`：

```java
package com.gigaxfer.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmokeTest {
    @Test
    void junit_and_assertj_wired() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }
}
```

- [ ] **Step 5: 跑測試**

Run: `mvn -q -pl gigaxfer-core test`
Expected: BUILD SUCCESS，1 test passed。

- [ ] **Step 6: Commit**

```bash
git add pom.xml gigaxfer-core/pom.xml .gitignore gigaxfer-core/src/test/java/com/gigaxfer/core/SmokeTest.java
git commit -m "build: maven multi-module skeleton with gigaxfer-core"
```

---

### Task 2: SHA-256 串流工具

**Files:**
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/digest/Sha256.java`
- Test: `gigaxfer-core/src/test/java/com/gigaxfer/core/digest/Sha256Test.java`

**Interfaces:**
- Produces: `Sha256.newDigest(): MessageDigest`、`Sha256.format(MessageDigest): String`（`sha256:<hex>`）、`Sha256.ofBytes(byte[]): String`、`Sha256.ofFile(Path): String`（串流）。

- [ ] **Step 1: 寫失敗測試**

```java
package com.gigaxfer.core.digest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Sha256Test {
    // SHA-256("abc") 的標準測試向量
    static final String ABC = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void ofBytes_matches_known_vector() {
        assertThat(Sha256.ofBytes("abc".getBytes(StandardCharsets.UTF_8))).isEqualTo(ABC);
    }

    @Test
    void ofFile_streams_and_matches_ofBytes(@TempDir Path dir) throws Exception {
        byte[] big = new byte[3 * 64 * 1024 + 17]; // 跨越多個 64 KB buffer
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 31);
        Path f = dir.resolve("big.bin");
        Files.write(f, big);
        assertThat(Sha256.ofFile(f)).isEqualTo(Sha256.ofBytes(big));
    }

    @Test
    void format_is_prefixed_lowercase_hex() {
        String s = Sha256.ofBytes(new byte[0]);
        assertThat(s).startsWith("sha256:").hasSize(7 + 64).matches("sha256:[0-9a-f]{64}");
    }
}
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `mvn -q -pl gigaxfer-core test -Dtest=Sha256Test`
Expected: 編譯失敗，`Sha256` 不存在。

- [ ] **Step 3: 實作**

```java
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
```

- [ ] **Step 4: 跑測試確認通過**

Run: `mvn -q -pl gigaxfer-core test -Dtest=Sha256Test`
Expected: 3 tests passed。

- [ ] **Step 5: Commit**

```bash
git add gigaxfer-core/src/main/java/com/gigaxfer/core/digest gigaxfer-core/src/test/java/com/gigaxfer/core/digest
git commit -m "feat(core): streaming SHA-256 digest utility"
```

---

### Task 3: FileIdentity

**Files:**
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/identity/FileIdentity.java`
- Test: `gigaxfer-core/src/test/java/com/gigaxfer/core/identity/FileIdentityTest.java`

**Interfaces:**
- Produces: `record FileIdentity(String sourceNode, String namespace, String logicalKey)`，建構時驗證；`IllegalArgumentException` 表示違反 SR-01 命名義務。

- [ ] **Step 1: 寫失敗測試**

```java
package com.gigaxfer.core.identity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileIdentityTest {
    @Test
    void accepts_plain_segments() {
        FileIdentity id = new FileIdentity("P3", "mes", "L123-R2.dat");
        assertThat(id.logicalKey()).isEqualTo("L123-R2.dat");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".hidden", "a/b", "a\0b"})
    void rejects_bad_segment_in_any_position(String bad) {
        assertThatThrownBy(() -> new FileIdentity(bad, "mes", "k")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FileIdentity("P3", bad, "k")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FileIdentity("P3", "mes", bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"L1.writing", "L1.tmp", "L1.manifest", "L1.manifest.x"})
    void rejects_reserved_suffix_in_logical_key(String bad) {
        assertThatThrownBy(() -> new FileIdentity("P3", "mes", bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void null_segment_rejected() {
        assertThatThrownBy(() -> new FileIdentity(null, "mes", "k")).isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `mvn -q -pl gigaxfer-core test -Dtest=FileIdentityTest`
Expected: 編譯失敗。

- [ ] **Step 3: 實作**

```java
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

    private static void requireSegment(String v, String name) {
        if (v == null || v.isEmpty() || v.startsWith(".") || v.indexOf('/') >= 0 || v.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " is not a valid path segment: " + v);
        }
    }
}
```

- [ ] **Step 4: 跑測試確認通過**

Run: `mvn -q -pl gigaxfer-core test -Dtest=FileIdentityTest`
Expected: all passed。

- [ ] **Step 5: Commit**

```bash
git add gigaxfer-core/src/main/java/com/gigaxfer/core/identity gigaxfer-core/src/test/java/com/gigaxfer/core/identity
git commit -m "feat(core): FileIdentity with SR-01 naming rules"
```

---

### Task 4: PathLayout

**Files:**
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/layout/PathLayout.java`
- Test: `gigaxfer-core/src/test/java/com/gigaxfer/core/layout/PathLayoutTest.java`

**Interfaces:**
- Consumes: `FileIdentity`、`Sha256.newDigest()`。
- Produces: `new PathLayout(Path root, ZoneId zone)`；`contentDir(FileIdentity, String dataClass, Instant)`、`manifestDir(FileIdentity)`、`manifestPath(FileIdentity)`、`manifestTmpPath(FileIdentity, UUID)`、`writingPath(Path dir, FileIdentity, UUID)`、`toContentPath(Path absolute): String`、`fromContentPath(String): Path`、`static bucket(String key): String`、`root(): Path`。

- [ ] **Step 1: 寫失敗測試**

```java
package com.gigaxfer.core.layout;

import com.gigaxfer.core.digest.Sha256;
import com.gigaxfer.core.identity.FileIdentity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathLayoutTest {
    final Path root = Path.of("/nas");
    final PathLayout layout = new PathLayout(root, ZoneOffset.UTC);
    final FileIdentity id = new FileIdentity("P3", "mes", "L123-R2");
    final Instant at = Instant.parse("2026-09-22T08:15:03Z");

    @Test
    void content_dir_is_source_ns_class_day_hour() {
        assertThat(layout.contentDir(id, "metrology", at))
            .isEqualTo(root.resolve("P3/mes/metrology/2026-09-22/08"));
    }

    @Test
    void content_dir_uses_configured_zone() {
        PathLayout taipei = new PathLayout(root, java.time.ZoneId.of("Asia/Taipei"));
        assertThat(taipei.contentDir(id, "metrology", at))
            .isEqualTo(root.resolve("P3/mes/metrology/2026-09-22/16"));
    }

    @Test
    void manifest_lives_in_hash_bucket_independent_of_time() {
        String bucket = PathLayout.bucket("L123-R2");
        assertThat(bucket).hasSize(3).matches("[0-9a-f]{3}");
        String expected = Sha256.ofBytes("L123-R2".getBytes(StandardCharsets.UTF_8)).substring(Sha256.PREFIX.length(), Sha256.PREFIX.length() + 3);
        assertThat(bucket).isEqualTo(expected);
        assertThat(layout.manifestPath(id)).isEqualTo(root.resolve("P3/mes/.manifest/" + bucket + "/L123-R2.manifest"));
    }

    @Test
    void temp_names_carry_uuid() {
        UUID u = UUID.fromString("11111111-2222-3333-4444-555555555555");
        assertThat(layout.writingPath(Path.of("/nas/x"), id, u)).isEqualTo(Path.of("/nas/x/L123-R2." + u + ".writing"));
        assertThat(layout.manifestTmpPath(id, u)).isEqualTo(layout.manifestDir(id).resolve("L123-R2.manifest." + u + ".tmp"));
    }

    @Test
    void content_path_round_trips_relative_to_root() {
        Path abs = layout.contentDir(id, "metrology", at).resolve("L123-R2");
        String rel = layout.toContentPath(abs);
        assertThat(rel).isEqualTo("P3/mes/metrology/2026-09-22/08/L123-R2");
        assertThat(layout.fromContentPath(rel)).isEqualTo(abs);
    }

    @Test
    void from_content_path_rejects_escape() {
        assertThatThrownBy(() -> layout.fromContentPath("../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `mvn -q -pl gigaxfer-core test -Dtest=PathLayoutTest`
Expected: 編譯失敗。

- [ ] **Step 3: 實作**

```java
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
import java.util.UUID;

/**
 * NFS 佈局（D2 修、D48、file-inventory）。
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
        this.root = root.toAbsolutePath().normalize();
        this.zone = zone;
    }

    public Path root() {
        return root;
    }

    public Path contentDir(FileIdentity id, String dataClass, Instant at) {
        ZonedDateTime t = at.atZone(zone);
        return root.resolve(id.sourceNode()).resolve(id.namespace()).resolve(dataClass)
            .resolve(DAY.format(t)).resolve(HOUR.format(t));
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
```

- [ ] **Step 4: 跑測試確認通過**

Run: `mvn -q -pl gigaxfer-core test -Dtest=PathLayoutTest`
Expected: 6 tests passed。

- [ ] **Step 5: Commit**

```bash
git add gigaxfer-core/src/main/java/com/gigaxfer/core/layout gigaxfer-core/src/test/java/com/gigaxfer/core/layout
git commit -m "feat(core): PathLayout for content, manifest bucket and temp names"
```

---

### Task 5: Manifest 與 ManifestCodec

**Files:**
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/manifest/Manifest.java`
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/manifest/ManifestCodec.java`
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/manifest/MalformedManifestException.java`
- Test: `gigaxfer-core/src/test/java/com/gigaxfer/core/manifest/ManifestCodecTest.java`

**Interfaces:**
- Consumes: `FileIdentity`。
- Produces: `record Manifest(int schemaVersion, String sourceNode, String namespace, String dataClass, String logicalKey, long size, String digest, String uuid, Instant sourceReadyAt, String contentPath)`，`Manifest.SCHEMA_VERSION = 1`，`identity(): FileIdentity`，`sameDeclaration(FileIdentity, String dataClass, long size, String digest): boolean`（D53 修 ① 四項比對）；`ManifestCodec.encode(Manifest): byte[]`（單行 + `\n`）、`decode(byte[]): Manifest` 丟 `MalformedManifestException extends IOException`。

- [ ] **Step 1: 寫失敗測試**

```java
package com.gigaxfer.core.manifest;

import com.gigaxfer.core.identity.FileIdentity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestCodecTest {
    final ManifestCodec codec = new ManifestCodec();
    final Manifest m = new Manifest(1, "P3", "mes", "metrology", "L123-R2", 1048576L,
        "sha256:" + "ab".repeat(32), "11111111-2222-3333-4444-555555555555",
        Instant.parse("2026-09-22T08:15:03.123Z"), "P3/mes/metrology/2026-09-22/08/L123-R2");

    @Test
    void encodes_single_line_snake_case_json() {
        String s = new String(codec.encode(m), StandardCharsets.UTF_8);
        assertThat(s).endsWith("\n");
        assertThat(s.substring(0, s.length() - 1)).doesNotContain("\n");
        assertThat(s).contains("\"schema_version\":1").contains("\"source_node\":\"P3\"").contains("\"data_class\":\"metrology\"")
            .contains("\"logical_key\":\"L123-R2\"").contains("\"source_ready_at\":\"2026-09-22T08:15:03.123Z\"")
            .contains("\"content_path\":\"P3/mes/metrology/2026-09-22/08/L123-R2\"");
    }

    @Test
    void round_trips() throws Exception {
        assertThat(codec.decode(codec.encode(m))).isEqualTo(m);
    }

    @Test
    void truncated_bytes_are_malformed() {
        byte[] full = codec.encode(m);
        byte[] half = Arrays.copyOf(full, full.length / 2);
        assertThatThrownBy(() -> codec.decode(half)).isInstanceOf(MalformedManifestException.class);
    }

    @Test
    void wrong_schema_version_is_malformed() {
        String s = new String(codec.encode(m), StandardCharsets.UTF_8).replace("\"schema_version\":1", "\"schema_version\":2");
        assertThatThrownBy(() -> codec.decode(s.getBytes(StandardCharsets.UTF_8))).isInstanceOf(MalformedManifestException.class);
    }

    @Test
    void missing_digest_is_malformed() {
        String s = new String(codec.encode(m), StandardCharsets.UTF_8).replace("\"digest\":\"sha256:" + "ab".repeat(32) + "\",", "");
        assertThatThrownBy(() -> codec.decode(s.getBytes(StandardCharsets.UTF_8))).isInstanceOf(MalformedManifestException.class);
    }

    @Test
    void same_declaration_compares_four_fields() {
        FileIdentity id = new FileIdentity("P3", "mes", "L123-R2");
        assertThat(m.sameDeclaration(id, "metrology", 1048576L, m.digest())).isTrue();
        assertThat(m.sameDeclaration(id, "other", 1048576L, m.digest())).isFalse();
        assertThat(m.sameDeclaration(id, "metrology", 1L, m.digest())).isFalse();
        assertThat(m.sameDeclaration(id, "metrology", 1048576L, "sha256:" + "00".repeat(32))).isFalse();
        assertThat(m.sameDeclaration(new FileIdentity("P4", "mes", "L123-R2"), "metrology", 1048576L, m.digest())).isFalse();
    }
}
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `mvn -q -pl gigaxfer-core test -Dtest=ManifestCodecTest`
Expected: 編譯失敗。

- [ ] **Step 3: 實作**

`Manifest.java`：

```java
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
```

`MalformedManifestException.java`：

```java
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
```

`ManifestCodec.java`：

```java
package com.gigaxfer.core.manifest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ManifestCodec {
    private final ObjectMapper mapper = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    public byte[] encode(Manifest m) {
        try {
            return (mapper.writeValueAsString(m) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unencodable manifest", e);
        }
    }

    public Manifest decode(byte[] bytes) throws MalformedManifestException {
        Manifest m;
        try {
            m = mapper.readValue(bytes, Manifest.class);
        } catch (IOException e) {
            throw new MalformedManifestException("manifest parse failed: " + e.getMessage(), e);
        }
        if (m.schemaVersion() != Manifest.SCHEMA_VERSION) throw new MalformedManifestException("schema_version " + m.schemaVersion());
        if (m.sourceNode() == null || m.namespace() == null || m.dataClass() == null || m.logicalKey() == null
            || m.digest() == null || m.uuid() == null || m.sourceReadyAt() == null || m.contentPath() == null) {
            throw new MalformedManifestException("manifest missing required field");
        }
        return m;
    }
}
```

- [ ] **Step 4: 跑測試確認通過**

Run: `mvn -q -pl gigaxfer-core test -Dtest=ManifestCodecTest`
Expected: 6 tests passed。

- [ ] **Step 5: Commit**

```bash
git add gigaxfer-core/src/main/java/com/gigaxfer/core/manifest gigaxfer-core/src/test/java/com/gigaxfer/core/manifest
git commit -m "feat(core): Manifest record and single-line JSON codec"
```

---

### Task 6: NFS 有界執行器（D51）

**Files:**
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/nfs/NfsExecutor.java`
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/nfs/NfsBusyException.java`
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/nfs/NfsTimeoutException.java`
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/nfs/BoundedNfsExecutor.java`
- Test: `gigaxfer-core/src/test/java/com/gigaxfer/core/nfs/BoundedNfsExecutorTest.java`

**Interfaces:**
- Produces: `interface NfsExecutor extends AutoCloseable { <T> T call(String op, IoCallable<T>) throws NfsBusyException, NfsTimeoutException, IOException; default void run(String op, IoRunnable); void close(); }`；`NfsBusyException(op)` / `NfsTimeoutException(op)` 皆有 `op()`；`new BoundedNfsExecutor(String name, int slots, Duration timeout)`，`inUse(): int`。

- [ ] **Step 1: 寫失敗測試**

```java
package com.gigaxfer.core.nfs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedNfsExecutorTest {

    @Test
    void returns_value_and_propagates_io_exception() throws Exception {
        try (BoundedNfsExecutor nfs = new BoundedNfsExecutor("t", 2, Duration.ofSeconds(1))) {
            assertThat(nfs.call("op", () -> 42)).isEqualTo(42);
            assertThatThrownBy(() -> nfs.call("op", () -> { throw new IOException("boom"); }))
                .isInstanceOf(IOException.class).hasMessage("boom");
        }
    }

    @Test
    void timeout_does_not_release_slot_and_full_pool_rejects_immediately() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        try (BoundedNfsExecutor nfs = new BoundedNfsExecutor("t", 1, Duration.ofMillis(100))) {
            assertThatThrownBy(() -> nfs.call("slow", () -> {
                release.await();
                finished.countDown();
                return null;
            })).isInstanceOf(NfsTimeoutException.class);

            // 槽位仍被卡住的 syscall 占用（D51：timeout 只是呼叫者不等）
            assertThat(nfs.inUse()).isEqualTo(1);

            long t0 = System.nanoTime();
            assertThatThrownBy(() -> nfs.call("next", () -> 1)).isInstanceOf(NfsBusyException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(50));

            release.countDown();
            assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(20); // 讓 worker 回到 idle
            assertThat(nfs.call("after", () -> 7)).isEqualTo(7);
        }
    }

    @Test
    void exceptions_carry_op_name() {
        try (BoundedNfsExecutor nfs = new BoundedNfsExecutor("t", 1, Duration.ofMillis(50))) {
            assertThatThrownBy(() -> nfs.call("link-key", () -> { Thread.sleep(500); return null; }))
                .isInstanceOf(NfsTimeoutException.class)
                .satisfies(e -> assertThat(((NfsTimeoutException) e).op()).isEqualTo("link-key"));
        }
    }
}
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `mvn -q -pl gigaxfer-core test -Dtest=BoundedNfsExecutorTest`
Expected: 編譯失敗。

- [ ] **Step 3: 實作**

`NfsExecutor.java`：

```java
package com.gigaxfer.core.nfs;

import java.io.IOException;

/**
 * 所有檔案系統操作的唯一入口（D51、SR-05）。
 * 實作必須：固定槽位、無佇列、滿了立即丟 NfsBusyException、timeout 丟 NfsTimeoutException 但不釋放槽位。
 */
public interface NfsExecutor extends AutoCloseable {

    <T> T call(String op, IoCallable<T> body) throws NfsBusyException, NfsTimeoutException, IOException;

    default void run(String op, IoRunnable body) throws NfsBusyException, NfsTimeoutException, IOException {
        call(op, () -> {
            body.run();
            return null;
        });
    }

    @Override
    void close();

    @FunctionalInterface
    interface IoCallable<T> {
        T call() throws Exception;
    }

    @FunctionalInterface
    interface IoRunnable {
        void run() throws Exception;
    }
}
```

`NfsBusyException.java`：

```java
package com.gigaxfer.core.nfs;

/** 有界執行器已滿：呼叫者立即得知，不排隊（D51）。 */
public final class NfsBusyException extends Exception {
    private final String op;

    public NfsBusyException(String op) {
        super("nfs pool exhausted at " + op);
        this.op = op;
    }

    public String op() {
        return op;
    }
}
```

`NfsTimeoutException.java`：

```java
package com.gigaxfer.core.nfs;

/** 操作結果未知：呼叫者不再等待，但底層 syscall 仍在進行、槽位仍被占用（D51、SR-04）。 */
public final class NfsTimeoutException extends Exception {
    private final String op;

    public NfsTimeoutException(String op) {
        super("nfs operation timed out: " + op);
        this.op = op;
    }

    public String op() {
        return op;
    }
}
```

`BoundedNfsExecutor.java`：

```java
package com.gigaxfer.core.nfs;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class BoundedNfsExecutor implements NfsExecutor {
    private final ThreadPoolExecutor pool;
    private final Duration timeout;

    public BoundedNfsExecutor(String name, int slots, Duration timeout) {
        AtomicInteger seq = new AtomicInteger();
        // core == max 且 SynchronousQueue：沒有 idle thread 可接手就直接 reject，不排隊
        this.pool = new ThreadPoolExecutor(slots, slots, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, name + "-nfs-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
        this.timeout = timeout;
    }

    @Override
    public <T> T call(String op, IoCallable<T> body) throws NfsBusyException, NfsTimeoutException, IOException {
        Future<T> future;
        try {
            future = pool.submit(body::call);
        } catch (RejectedExecutionException e) {
            throw new NfsBusyException(op);
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 刻意不 cancel：hard mount 下卡住的 thread 殺不掉，槽位只在 syscall 回來才釋放
            throw new NfsTimeoutException(op);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException io) throw io;
            if (c instanceof RuntimeException re) throw re;
            if (c instanceof Error er) throw er;
            throw new IOException(op + " failed", c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NfsTimeoutException(op);
        }
    }

    /** 目前被占用的槽位數（含已 timeout 但 syscall 未返回者）。 */
    public int inUse() {
        return pool.getActiveCount();
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
```

- [ ] **Step 4: 跑測試確認通過**

Run: `mvn -q -pl gigaxfer-core test -Dtest=BoundedNfsExecutorTest`
Expected: 3 tests passed。

- [ ] **Step 5: Commit**

```bash
git add gigaxfer-core/src/main/java/com/gigaxfer/core/nfs gigaxfer-core/src/test/java/com/gigaxfer/core/nfs
git commit -m "feat(core): bounded NFS executor with no-queue reject and non-releasing timeout"
```

---

### Task 7: beginWrite、串流寫入、discard

**Files:**
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/store/WriteGate.java`
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/store/WriteRejectedException.java`
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/store/FinalizeResult.java`
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/store/FailureReason.java`
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/store/LocalStore.java`
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/store/WriteHandle.java`（本 task 只有 stream / discard，finalize 於 Task 8 補）
- Test: `gigaxfer-core/src/test/java/com/gigaxfer/core/store/BeginWriteTest.java`
- Test helper: `gigaxfer-core/src/test/java/com/gigaxfer/core/store/MutableClock.java`

**Interfaces:**
- Consumes: `PathLayout`、`NfsExecutor`、`FileIdentity`、`Sha256`。
- Produces:
  - `@FunctionalInterface WriteGate { Optional<String> rejectReason(String namespace, String dataClass); static WriteGate open(); }`
  - `WriteRejectedException(Reason, String)`，`Reason { REJECTED, UNAVAILABLE, IO }`，`reason()`。
  - `sealed FinalizeResult`：`Success(FileIdentity identity, String contentPath)`、`Failure(FailureReason reason, String detail)`、`PendingConfirmation(String op, String detail)`；`enum FailureReason { CONFLICT, DECLARATION_EXPIRED, IO }`。
  - `new LocalStore(String sourceNode, PathLayout layout, NfsExecutor nfs, WriteGate gate, Clock clock)`；`beginWrite(String namespace, String dataClass, String logicalKey): WriteHandle throws WriteRejectedException`；`LocalStore.DECLARATION_MAX_AGE = Duration.ofDays(7)`。
  - `WriteHandle`：`identity(): FileIdentity`、`stream(): OutputStream`、`writingPath(): Path`、`discard()`、`finalize(): FinalizeResult`（Task 8）、`close()` 只關通道不刪檔。

- [ ] **Step 1: 寫測試用 MutableClock**

```java
package com.gigaxfer.core.store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
        this.now = start;
    }

    void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
```

- [ ] **Step 2: 寫失敗測試**

```java
package com.gigaxfer.core.store;

import com.gigaxfer.core.layout.PathLayout;
import com.gigaxfer.core.nfs.BoundedNfsExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BeginWriteTest {
    @TempDir Path root;
    MutableClock clock = new MutableClock(Instant.parse("2026-09-22T08:15:03Z"));
    BoundedNfsExecutor nfs;
    PathLayout layout;

    @BeforeEach
    void setUp() {
        nfs = new BoundedNfsExecutor("t", 4, Duration.ofSeconds(5));
        layout = new PathLayout(root, ZoneOffset.UTC);
    }

    @AfterEach
    void tearDown() {
        nfs.close();
    }

    LocalStore store(WriteGate gate) {
        return new LocalStore("P3", layout, nfs, gate, clock);
    }

    @Test
    void creates_uuid_writing_file_in_current_hour_dir() throws Exception {
        WriteHandle h = store(WriteGate.open()).beginWrite("mes", "metrology", "L1");
        Path dir = root.resolve("P3/mes/metrology/2026-09-22/08");
        assertThat(h.writingPath().getParent()).isEqualTo(dir);
        assertThat(h.writingPath().getFileName().toString()).matches("L1\\.[0-9a-f-]{36}\\.writing");
        assertThat(h.writingPath()).exists();
        h.close();
    }

    @Test
    void stream_writes_bytes_to_writing_file() throws Exception {
        WriteHandle h = store(WriteGate.open()).beginWrite("mes", "metrology", "L1");
        h.stream().write("hello".getBytes(StandardCharsets.UTF_8));
        h.stream().flush();
        assertThat(h.writingPath()).hasContent("hello");
        h.close();
    }

    @Test
    void discard_removes_writing_file_and_nothing_else() throws Exception {
        WriteHandle h = store(WriteGate.open()).beginWrite("mes", "metrology", "L1");
        h.stream().write(new byte[10]);
        h.discard();
        assertThat(h.writingPath()).doesNotExist();
        try (Stream<Path> s = Files.walk(root)) {
            assertThat(s.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void gate_rejection_creates_nothing() {
        WriteGate gate = (ns, cls) -> Optional.of("data class not registered: " + cls);
        assertThatThrownBy(() -> store(gate).beginWrite("mes", "unknown", "L1"))
            .isInstanceOf(WriteRejectedException.class)
            .satisfies(e -> assertThat(((WriteRejectedException) e).reason()).isEqualTo(WriteRejectedException.Reason.REJECTED));
        assertThat(root.resolve("P3")).doesNotExist();
    }

    @Test
    void invalid_logical_key_is_rejected_before_touching_nfs() {
        assertThatThrownBy(() -> store(WriteGate.open()).beginWrite("mes", "metrology", "bad.writing"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(root.resolve("P3")).doesNotExist();
    }
}
```

- [ ] **Step 3: 跑測試確認失敗**

Run: `mvn -q -pl gigaxfer-core test -Dtest=BeginWriteTest`
Expected: 編譯失敗。

- [ ] **Step 4: 實作介面與結果型別**

`WriteGate.java`：

```java
package com.gigaxfer.core.store;

import java.util.Optional;

/** beginWrite 前的閘門：Policy 登錄（Q16/Q17）與容量（D21）由 library starter 實作；core 只定義契約。 */
@FunctionalInterface
public interface WriteGate {
    /** 空 = 放行；非空 = 拒絕原因。 */
    Optional<String> rejectReason(String namespace, String dataClass);

    static WriteGate open() {
        return (ns, cls) -> Optional.empty();
    }
}
```

`WriteRejectedException.java`：

```java
package com.gigaxfer.core.store;

public final class WriteRejectedException extends Exception {
    public enum Reason { REJECTED, UNAVAILABLE, IO }

    private final Reason reason;

    public WriteRejectedException(Reason reason, String detail) {
        super(reason + ": " + detail);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
```

`FailureReason.java`：

```java
package com.gigaxfer.core.store;

public enum FailureReason { CONFLICT, DECLARATION_EXPIRED, IO }
```

`FinalizeResult.java`：

```java
package com.gigaxfer.core.store;

import com.gigaxfer.core.identity.FileIdentity;

/** SR-04 三態。PendingConfirmation 表示結果未知，Application 重呼 finalize() 查證。 */
public sealed interface FinalizeResult {
    record Success(FileIdentity identity, String contentPath) implements FinalizeResult {}

    record Failure(FailureReason reason, String detail) implements FinalizeResult {}

    record PendingConfirmation(String op, String detail) implements FinalizeResult {}
}
```

- [ ] **Step 5: 實作 LocalStore**

```java
package com.gigaxfer.core.store;

import com.gigaxfer.core.identity.FileIdentity;
import com.gigaxfer.core.layout.PathLayout;
import com.gigaxfer.core.manifest.ManifestCodec;
import com.gigaxfer.core.nfs.NfsBusyException;
import com.gigaxfer.core.nfs.NfsExecutor;
import com.gigaxfer.core.nfs.NfsTimeoutException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Application 端 Storage Access contract 的核心（SR-01、D23）。 */
public final class LocalStore {
    /** v1 固定常數（D30 修 5、D53 修）：宣告後超過此年齡不得再次嘗試發布。 */
    public static final Duration DECLARATION_MAX_AGE = Duration.ofDays(7);

    final String sourceNode;
    final PathLayout layout;
    final NfsExecutor nfs;
    final Clock clock;
    final ManifestCodec codec = new ManifestCodec();
    private final WriteGate gate;

    public LocalStore(String sourceNode, PathLayout layout, NfsExecutor nfs, WriteGate gate, Clock clock) {
        this.sourceNode = sourceNode;
        this.layout = layout;
        this.nfs = nfs;
        this.gate = gate;
        this.clock = clock;
    }

    public WriteHandle beginWrite(String namespace, String dataClass, String logicalKey) throws WriteRejectedException {
        FileIdentity id = new FileIdentity(sourceNode, namespace, logicalKey); // 命名違約在碰 NFS 前就丟 IllegalArgumentException
        Optional<String> reject = gate.rejectReason(namespace, dataClass);
        if (reject.isPresent()) throw new WriteRejectedException(WriteRejectedException.Reason.REJECTED, reject.get());

        UUID uuid = UUID.randomUUID();
        Path dir = layout.contentDir(id, dataClass, clock.instant());
        Path writing = layout.writingPath(dir, id, uuid);
        try {
            FileChannel channel = nfs.call("open-writing", () -> {
                Files.createDirectories(dir);
                return FileChannel.open(writing, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            });
            return new WriteHandle(this, id, dataClass, uuid, writing, channel);
        } catch (NfsBusyException | NfsTimeoutException e) {
            throw new WriteRejectedException(WriteRejectedException.Reason.UNAVAILABLE, e.getMessage());
        } catch (IOException e) {
            throw new WriteRejectedException(WriteRejectedException.Reason.IO, e.toString());
        }
    }
}
```

- [ ] **Step 6: 實作 WriteHandle（stream / discard / close；finalize 先丟 UnsupportedOperationException）**

```java
package com.gigaxfer.core.store;

import com.gigaxfer.core.digest.Sha256;
import com.gigaxfer.core.identity.FileIdentity;
import com.gigaxfer.core.nfs.NfsBusyException;
import com.gigaxfer.core.nfs.NfsTimeoutException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.UUID;

/** 一次寫入的生命週期：Writing → Finalize → Source Ready，或 Discard。 */
public final class WriteHandle implements AutoCloseable {
    private static final int BUFFER = 64 * 1024;

    private final LocalStore store;
    private final FileIdentity id;
    private final String dataClass;
    private final UUID uuid;
    private final Path writing;
    private final FileChannel channel;
    private final MessageDigest md = Sha256.newDigest();
    private final OutputStream stream;
    private long size;
    private String digest; // 第①步 fsync 後固定，之後不再改（IR-01）
    private boolean discarded;

    WriteHandle(LocalStore store, FileIdentity id, String dataClass, UUID uuid, Path writing, FileChannel channel) {
        this.store = store;
        this.id = id;
        this.dataClass = dataClass;
        this.uuid = uuid;
        this.writing = writing;
        this.channel = channel;
        this.stream = new java.io.BufferedOutputStream(new ChannelStream(), BUFFER);
    }

    public FileIdentity identity() {
        return id;
    }

    public Path writingPath() {
        return writing;
    }

    /** Application 寫內容的串流；每次底層 write 經有界執行器。 */
    public OutputStream stream() {
        return stream;
    }

    /** 只允許 Finalize 前（CONTEXT.md Discard）。 */
    public void discard() throws IOException {
        if (digest != null) throw new IllegalStateException("cannot discard after finalize started");
        discarded = true;
        try {
            store.nfs.run("discard-writing", () -> {
                channel.close();
                Files.deleteIfExists(writing);
            });
        } catch (NfsBusyException | NfsTimeoutException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    public FinalizeResult finalize() {
        throw new UnsupportedOperationException("Task 8");
    }

    /** 只關通道，不刪任何檔：PENDING_CONFIRMATION 後 Application 仍可重呼 finalize。 */
    @Override
    public void close() throws IOException {
        channel.close();
    }

    private final class ChannelStream extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (discarded) throw new IOException("handle discarded");
            ByteBuffer buf = ByteBuffer.wrap(b, off, len);
            try {
                store.nfs.run("write", () -> {
                    while (buf.hasRemaining()) channel.write(buf);
                });
            } catch (NfsBusyException | NfsTimeoutException e) {
                throw new IOException(e.getMessage(), e);
            }
            md.update(b, off, len);
            size += len;
        }
    }

    // 供 Task 8 使用
    String dataClass() { return dataClass; }
    UUID uuid() { return uuid; }
    FileChannel channel() { return channel; }
    MessageDigest md() { return md; }
    long size() { return size; }
    String digest() { return digest; }
    void digest(String d) { this.digest = d; }
}
```

- [ ] **Step 7: 跑測試確認通過**

Run: `mvn -q -pl gigaxfer-core test -Dtest=BeginWriteTest`
Expected: 5 tests passed。

- [ ] **Step 8: Commit**

```bash
git add gigaxfer-core/src/main/java/com/gigaxfer/core/store gigaxfer-core/src/test/java/com/gigaxfer/core/store
git commit -m "feat(core): LocalStore.beginWrite, streaming write and discard"
```

---

### Task 8: Finalize 正常路徑（①②③④）

**Files:**
- Modify: `gigaxfer-core/src/main/java/com/gigaxfer/core/store/WriteHandle.java`（取代 `finalize()` 與補 private 方法）
- Test: `gigaxfer-core/src/test/java/com/gigaxfer/core/store/FinalizeHappyPathTest.java`

**Interfaces:**
- Consumes: Task 7 的 `WriteHandle` 內部欄位、`ManifestCodec`、`Manifest`、`PathLayout`、`Sha256.ofFile`。
- Produces: `WriteHandle.finalize(): FinalizeResult`，Success 時 `contentPath` = manifest 的 `content_path`。

- [ ] **Step 1: 寫失敗測試**

```java
package com.gigaxfer.core.store;

import com.gigaxfer.core.digest.Sha256;
import com.gigaxfer.core.identity.FileIdentity;
import com.gigaxfer.core.layout.PathLayout;
import com.gigaxfer.core.manifest.Manifest;
import com.gigaxfer.core.manifest.ManifestCodec;
import com.gigaxfer.core.nfs.BoundedNfsExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FinalizeHappyPathTest {
    @TempDir Path root;
    MutableClock clock = new MutableClock(Instant.parse("2026-09-22T08:15:03Z"));
    BoundedNfsExecutor nfs;
    PathLayout layout;
    LocalStore store;
    final FileIdentity id = new FileIdentity("P3", "mes", "L123-R2");

    @BeforeEach
    void setUp() {
        nfs = new BoundedNfsExecutor("t", 4, Duration.ofSeconds(5));
        layout = new PathLayout(root, ZoneOffset.UTC);
        store = new LocalStore("P3", layout, nfs, WriteGate.open(), clock);
    }

    @AfterEach
    void tearDown() {
        nfs.close();
    }

    @Test
    void publishes_key_and_manifest_and_cleans_temps() throws Exception {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        WriteHandle h = store.beginWrite("mes", "metrology", "L123-R2");
        h.stream().write(content);

        FinalizeResult r = h.finalize();

        assertThat(r).isInstanceOf(FinalizeResult.Success.class);
        FinalizeResult.Success s = (FinalizeResult.Success) r;
        assertThat(s.identity()).isEqualTo(id);
        assertThat(s.contentPath()).isEqualTo("P3/mes/metrology/2026-09-22/08/L123-R2");

        Path key = root.resolve("P3/mes/metrology/2026-09-22/08/L123-R2");
        assertThat(key).hasBinaryContent(content);

        Manifest m = new ManifestCodec().decode(Files.readAllBytes(layout.manifestPath(id)));
        assertThat(m.size()).isEqualTo(5);
        assertThat(m.digest()).isEqualTo(Sha256.ofBytes(content));
        assertThat(m.dataClass()).isEqualTo("metrology");
        assertThat(m.sourceReadyAt()).isEqualTo(clock.instant());
        assertThat(m.contentPath()).isEqualTo(s.contentPath());
        assertThat(m.uuid()).isEqualTo(h.uuid().toString());

        try (Stream<Path> s1 = Files.list(key.getParent())) {
            assertThat(s1).containsExactly(key); // 沒有 .writing 殘留
        }
        try (Stream<Path> s2 = Files.list(layout.manifestDir(id))) {
            assertThat(s2).containsExactly(layout.manifestPath(id)); // 沒有 .tmp 殘留
        }
    }

    @Test
    void empty_file_is_a_valid_ready_file() throws Exception {
        WriteHandle h = store.beginWrite("mes", "metrology", "EMPTY");
        assertThat(h.finalize()).isInstanceOf(FinalizeResult.Success.class);
        assertThat(root.resolve("P3/mes/metrology/2026-09-22/08/EMPTY")).exists().isEmptyFile();
    }

    @Test
    void large_content_streams_without_buffering_whole_file() throws Exception {
        byte[] chunk = new byte[1 << 20]; // 1 MB × 8 = 8 MB
        for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) i;
        WriteHandle h = store.beginWrite("mes", "metrology", "BIG");
        for (int i = 0; i < 8; i++) h.stream().write(chunk);
        FinalizeResult r = h.finalize();
        assertThat(r).isInstanceOf(FinalizeResult.Success.class);
        Path key = root.resolve("P3/mes/metrology/2026-09-22/08/BIG");
        assertThat(Files.size(key)).isEqualTo(8L << 20);
        Manifest m = new ManifestCodec().decode(Files.readAllBytes(layout.manifestPath(new FileIdentity("P3", "mes", "BIG"))));
        assertThat(m.digest()).isEqualTo(Sha256.ofFile(key));
    }

    @Test
    void finalize_twice_on_same_handle_is_idempotent_success() throws Exception {
        WriteHandle h = store.beginWrite("mes", "metrology", "L123-R2");
        h.stream().write("x".getBytes(StandardCharsets.UTF_8));
        assertThat(h.finalize()).isInstanceOf(FinalizeResult.Success.class);
        assertThat(h.finalize()).isInstanceOf(FinalizeResult.Success.class);
        assertThat(root.resolve("P3/mes/metrology/2026-09-22/08/L123-R2")).hasContent("x");
    }
}
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `mvn -q -pl gigaxfer-core test -Dtest=FinalizeHappyPathTest`
Expected: FAIL，`UnsupportedOperationException: Task 8`。

- [ ] **Step 3: 實作 finalize**

把 `WriteHandle` 的 `finalize()` 換成以下，並加上兩個 private 方法與 import（`Manifest`、`MalformedManifestException`、`Instant`、`Duration`、`StandardOpenOption`、`FileAlreadyExistsException`、`NoSuchFileException`）：

```java
    /**
     * D3（v2）+ D44 + D48 修 + D53 修：
     * ① fsync 暫存、固定 digest
     * ② 宣告：tmp manifest + fsync → link 成 <key>.manifest（EEXIST → 四項比對 → 沿用既有 content_path）
     *    → rediscovery：<key> 已在且 digest 符 → SUCCESS；不在且宣告超過 N → DECLARATION_EXPIRED
     * ③ link(.writing → content_path/<key>) = commit point
     * ④ best-effort 清暫存
     * 任一步 timeout / pool 滿 → PENDING_CONFIRMATION；重呼走同一序列。
     */
    public FinalizeResult finalize() {
        if (discarded) return new FinalizeResult.Failure(FailureReason.IO, "handle discarded");
        try {
            // ①
            if (digest == null) {
                stream.flush();
                store.nfs.run("fsync-writing", () -> {
                    channel.force(true);
                    channel.close();
                });
                digest = Sha256.format(md);
            }

            // ②
            Path manifestPath = store.layout.manifestPath(id);
            Path tmp = store.layout.manifestTmpPath(id, uuid);
            Instant declaredAt = store.clock.instant();
            Path myContentDir = store.layout.contentDir(id, dataClass, declaredAt);
            Manifest mine = new Manifest(Manifest.SCHEMA_VERSION, id.sourceNode(), id.namespace(), dataClass, id.logicalKey(),
                size, digest, uuid.toString(), declaredAt, store.layout.toContentPath(myContentDir.resolve(id.logicalKey())));
            byte[] bytes = store.codec.encode(mine);
            store.nfs.run("write-manifest-tmp", () -> {
                Files.createDirectories(manifestPath.getParent());
                Files.createDirectories(myContentDir);
                Files.deleteIfExists(tmp); // 重試時舊 tmp 可能半截；刪名字不影響已 link 的 manifest inode
                try (FileChannel c = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    ByteBuffer b = ByteBuffer.wrap(bytes);
                    while (b.hasRemaining()) c.write(b);
                    c.force(true);
                }
            });

            Manifest declared;
            try {
                store.nfs.run("link-manifest", () -> Files.createLink(manifestPath, tmp));
                declared = mine;
            } catch (FileAlreadyExistsException e) {
                declared = store.nfs.call("read-manifest", () -> store.codec.decode(Files.readAllBytes(manifestPath)));
                if (!declared.sameDeclaration(id, dataClass, size, digest)) {
                    cleanupTemps();
                    return new FinalizeResult.Failure(FailureReason.CONFLICT,
                        "identity already declared with different content: " + declared.digest() + " size=" + declared.size());
                }
            }
            bestEffort("unlink-manifest-tmp", () -> Files.deleteIfExists(tmp));

            Path contentPath = store.layout.fromContentPath(declared.contentPath());

            // rediscovery：已發布？
            if (store.nfs.call("stat-key", () -> Files.exists(contentPath))) {
                return verifyPublished(contentPath, declared);
            }

            // 需要再次嘗試發布：年齡只約束再次嘗試（D53 修）
            Instant mtime = store.nfs.call("stat-manifest", () -> Files.getLastModifiedTime(manifestPath).toInstant());
            if (Duration.between(mtime, store.clock.instant()).compareTo(LocalStore.DECLARATION_MAX_AGE) > 0) {
                cleanupTemps();
                return new FinalizeResult.Failure(FailureReason.DECLARATION_EXPIRED, "declared at " + mtime + ", use a new logical key");
            }

            // ③ commit point
            try {
                store.nfs.run("link-key", () -> {
                    Files.createDirectories(contentPath.getParent());
                    Files.createLink(contentPath, writing);
                });
            } catch (FileAlreadyExistsException e) {
                return verifyPublished(contentPath, declared);
            } catch (NoSuchFileException e) {
                // D51 修 2：link 尚未送出且來源已不存在（例如超 TTL 被清道夫刪）→ FAILURE
                return new FinalizeResult.Failure(FailureReason.IO, "writing file missing before link: " + writing);
            }

            // ④
            cleanupTemps();
            return new FinalizeResult.Success(id, declared.contentPath());

        } catch (NfsTimeoutException e) {
            return new FinalizeResult.PendingConfirmation(e.op(), "nfs timeout");
        } catch (NfsBusyException e) {
            return new FinalizeResult.PendingConfirmation(e.op(), "nfs pool exhausted");
        } catch (IOException e) {
            return new FinalizeResult.Failure(FailureReason.IO, e.toString());
        }
    }

    /** 重試路徑的當下證據（D44）：重讀 <key> 算 digest 對 manifest。 */
    private FinalizeResult verifyPublished(Path contentPath, Manifest declared) throws NfsBusyException, NfsTimeoutException, IOException {
        String actual = store.nfs.call("digest-key", () -> Sha256.ofFile(contentPath));
        if (!actual.equals(declared.digest())) {
            cleanupTemps();
            return new FinalizeResult.Failure(FailureReason.CONFLICT, "published content " + actual + " != declared " + declared.digest());
        }
        cleanupTemps();
        return new FinalizeResult.Success(id, declared.contentPath());
    }

    private void cleanupTemps() {
        bestEffort("unlink-writing", () -> Files.deleteIfExists(writing));
        bestEffort("unlink-manifest-tmp", () -> Files.deleteIfExists(store.layout.manifestTmpPath(id, uuid)));
    }

    private void bestEffort(String op, com.gigaxfer.core.nfs.NfsExecutor.IoRunnable r) {
        try {
            store.nfs.run(op, r);
        } catch (Exception ignored) {
            // 清道夫兜底（D35）
        }
    }
```

同時把 `finalize()` 內用到的 `stream.flush()` 改為可重入：`BufferedOutputStream.flush()` 已關通道時會丟 `ClosedChannelException`，因此在 `digest != null` 分支不呼叫 flush（上面程式碼已如此安排）。

- [ ] **Step 4: 跑測試確認通過**

Run: `mvn -q -pl gigaxfer-core test -Dtest=FinalizeHappyPathTest`
Expected: 4 tests passed。

- [ ] **Step 5: 跑全部**

Run: `mvn -q -pl gigaxfer-core test`
Expected: all passed。

- [ ] **Step 6: Commit**

```bash
git add gigaxfer-core/src/main/java/com/gigaxfer/core/store/WriteHandle.java gigaxfer-core/src/test/java/com/gigaxfer/core/store/FinalizeHappyPathTest.java
git commit -m "feat(core): Finalize protocol — tmp+link manifest, cross-dir link publish, temp cleanup"
```

---

### Task 9: Finalize 的故障窗口與衝突（F1b、F2、F2b、F3、F5、F5b）

**Files:**
- Test helper: `gigaxfer-core/src/test/java/com/gigaxfer/core/store/FaultInjectingNfs.java`
- Test: `gigaxfer-core/src/test/java/com/gigaxfer/core/store/FinalizeRecoveryTest.java`

**Interfaces:**
- Consumes: `NfsExecutor`（裝飾）、Task 8 的 `finalize()`。
- Produces: 無新 production code；若測試揭露缺陷，修 `WriteHandle`。

- [ ] **Step 1: 寫故障注入裝飾器**

```java
package com.gigaxfer.core.store;

import com.gigaxfer.core.nfs.NfsBusyException;
import com.gigaxfer.core.nfs.NfsExecutor;
import com.gigaxfer.core.nfs.NfsTimeoutException;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 一次性故障：dropBefore = 操作根本沒送出；dropAfter = 操作已完成但回覆遺失。 */
final class FaultInjectingNfs implements NfsExecutor {
    private final NfsExecutor inner;
    private final Map<String, Boolean> before = new ConcurrentHashMap<>();
    private final Map<String, Boolean> after = new ConcurrentHashMap<>();

    FaultInjectingNfs(NfsExecutor inner) {
        this.inner = inner;
    }

    void dropBefore(String op) {
        before.put(op, Boolean.TRUE);
    }

    void dropAfter(String op) {
        after.put(op, Boolean.TRUE);
    }

    @Override
    public <T> T call(String op, IoCallable<T> body) throws NfsBusyException, NfsTimeoutException, IOException {
        if (before.remove(op) != null) throw new NfsTimeoutException(op);
        T result = inner.call(op, body);
        if (after.remove(op) != null) throw new NfsTimeoutException(op);
        return result;
    }

    @Override
    public void close() {
        inner.close();
    }
}
```

- [ ] **Step 2: 寫失敗測試**

```java
package com.gigaxfer.core.store;

import com.gigaxfer.core.identity.FileIdentity;
import com.gigaxfer.core.layout.PathLayout;
import com.gigaxfer.core.nfs.BoundedNfsExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FinalizeRecoveryTest {
    @TempDir Path root;
    MutableClock clock = new MutableClock(Instant.parse("2026-09-22T08:15:03Z"));
    BoundedNfsExecutor real;
    FaultInjectingNfs nfs;
    PathLayout layout;
    LocalStore store;
    final FileIdentity id = new FileIdentity("P3", "mes", "L1");
    final byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
    final Path key08 = Path.of("P3/mes/metrology/2026-09-22/08/L1");

    @BeforeEach
    void setUp() {
        real = new BoundedNfsExecutor("t", 4, Duration.ofSeconds(5));
        nfs = new FaultInjectingNfs(real);
        layout = new PathLayout(root, ZoneOffset.UTC);
        store = new LocalStore("P3", layout, nfs, WriteGate.open(), clock);
    }

    @AfterEach
    void tearDown() {
        real.close();
    }

    WriteHandle write(byte[] bytes) throws Exception {
        WriteHandle h = store.beginWrite("mes", "metrology", "L1");
        h.stream().write(bytes);
        return h;
    }

    long regularFiles() throws Exception {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void F2_link_not_sent_then_retry_publishes() throws Exception {
        WriteHandle h = write(content);
        nfs.dropBefore("link-key");
        FinalizeResult first = h.finalize();
        assertThat(first).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        assertThat(((FinalizeResult.PendingConfirmation) first).op()).isEqualTo("link-key");
        assertThat(layout.manifestPath(id)).exists();
        assertThat(root.resolve(key08)).doesNotExist();
        assertThat(h.writingPath()).exists();

        assertThat(h.finalize()).isInstanceOf(FinalizeResult.Success.class);
        assertThat(root.resolve(key08)).hasBinaryContent(content);
        assertThat(h.writingPath()).doesNotExist();
    }

    @Test
    void F3_link_done_but_reply_lost_then_retry_is_success_without_duplicate() throws Exception {
        WriteHandle h = write(content);
        nfs.dropAfter("link-key");
        assertThat(h.finalize()).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        assertThat(root.resolve(key08)).hasBinaryContent(content);

        assertThat(h.finalize()).isInstanceOf(FinalizeResult.Success.class);
        assertThat(regularFiles()).isEqualTo(2); // <key> + manifest，無殘留
    }

    @Test
    void manifest_link_reply_lost_then_retry_continues_with_same_declaration() throws Exception {
        WriteHandle h = write(content);
        nfs.dropAfter("link-manifest");
        assertThat(h.finalize()).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        clock.advance(Duration.ofHours(3)); // 重試落在不同小時，content_path 仍以第一次宣告為準（D48 修）
        FinalizeResult r = h.finalize();
        assertThat(r).isInstanceOf(FinalizeResult.Success.class);
        assertThat(((FinalizeResult.Success) r).contentPath()).isEqualTo(key08.toString());
        assertThat(root.resolve(key08)).hasBinaryContent(content);
        assertThat(regularFiles()).isEqualTo(2);
    }

    @Test
    void F5_same_identity_different_content_is_conflict_and_leaves_original() throws Exception {
        assertThat(write(content).finalize()).isInstanceOf(FinalizeResult.Success.class);
        WriteHandle h2 = write("different".getBytes(StandardCharsets.UTF_8));
        FinalizeResult r = h2.finalize();
        assertThat(r).isInstanceOf(FinalizeResult.Failure.class);
        assertThat(((FinalizeResult.Failure) r).reason()).isEqualTo(FailureReason.CONFLICT);
        assertThat(root.resolve(key08)).hasBinaryContent(content);
        assertThat(h2.writingPath()).doesNotExist();
        assertThat(regularFiles()).isEqualTo(2);
    }

    @Test
    void same_identity_same_content_from_new_handle_next_day_is_idempotent_success() throws Exception {
        assertThat(write(content).finalize()).isInstanceOf(FinalizeResult.Success.class);
        clock.advance(Duration.ofDays(1));
        WriteHandle h2 = write(content);
        FinalizeResult r = h2.finalize();
        assertThat(r).isInstanceOf(FinalizeResult.Success.class);
        assertThat(((FinalizeResult.Success) r).contentPath()).isEqualTo(key08.toString());
        assertThat(h2.writingPath()).doesNotExist();
        assertThat(regularFiles()).isEqualTo(2);
    }

    @Test
    void F2b_retry_after_declaration_older_than_7_days_is_expired() throws Exception {
        WriteHandle h = write(content);
        nfs.dropBefore("link-key");
        assertThat(h.finalize()).isInstanceOf(FinalizeResult.PendingConfirmation.class);

        Instant declared = clock.instant();
        Files.setLastModifiedTime(layout.manifestPath(id), FileTime.from(declared));
        clock.advance(Duration.ofDays(8));

        FinalizeResult r = h.finalize();
        assertThat(r).isInstanceOf(FinalizeResult.Failure.class);
        assertThat(((FinalizeResult.Failure) r).reason()).isEqualTo(FailureReason.DECLARATION_EXPIRED);
        assertThat(root.resolve(key08)).doesNotExist();
        assertThat(layout.manifestPath(id)).exists(); // manifest 永不刪（D53）
    }

    @Test
    void scenario_11_published_day_1_retry_day_8_is_success_not_expired() throws Exception {
        WriteHandle h = write(content);
        nfs.dropAfter("link-key");
        assertThat(h.finalize()).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        Files.setLastModifiedTime(layout.manifestPath(id), FileTime.from(clock.instant()));
        clock.advance(Duration.ofDays(8));
        assertThat(h.finalize()).isInstanceOf(FinalizeResult.Success.class);
    }

    @Test
    void F5b_published_file_corrupted_then_same_content_retry_is_conflict() throws Exception {
        assertThat(write(content).finalize()).isInstanceOf(FinalizeResult.Success.class);
        Files.write(root.resolve(key08), "corrupt".getBytes(StandardCharsets.UTF_8));
        FinalizeResult r = write(content).finalize();
        assertThat(r).isInstanceOf(FinalizeResult.Failure.class);
        assertThat(((FinalizeResult.Failure) r).reason()).isEqualTo(FailureReason.CONFLICT);
    }

    @Test
    void F1b_writing_file_removed_before_link_is_failure_not_pending() throws Exception {
        WriteHandle h = write(content);
        nfs.dropBefore("link-key");
        assertThat(h.finalize()).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        Files.delete(h.writingPath()); // 模擬清道夫依 TTL 刪除
        FinalizeResult r = h.finalize();
        assertThat(r).isInstanceOf(FinalizeResult.Failure.class);
        assertThat(((FinalizeResult.Failure) r).reason()).isEqualTo(FailureReason.IO);
        assertThat(root.resolve(key08)).doesNotExist();
    }

    @Test
    void F4_half_written_tmp_from_previous_attempt_is_replaced_not_linked() throws Exception {
        WriteHandle h = write(content);
        nfs.dropAfter("write-manifest-tmp");
        assertThat(h.finalize()).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        Path tmp = layout.manifestTmpPath(id, h.uuid());
        Files.write(tmp, "{\"schema_version\":1,\"sou".getBytes(StandardCharsets.UTF_8)); // 半截
        assertThat(h.finalize()).isInstanceOf(FinalizeResult.Success.class);
        assertThat(store.codec.decode(Files.readAllBytes(layout.manifestPath(id))).digest())
            .isEqualTo(com.gigaxfer.core.digest.Sha256.ofBytes(content));
    }
}
```

- [ ] **Step 3: 跑測試**

Run: `mvn -q -pl gigaxfer-core test -Dtest=FinalizeRecoveryTest`
Expected: 全部通過。若任一失敗，缺陷在 `WriteHandle.finalize()`，依失敗訊息修正後重跑；不得修改測試的預期。

- [ ] **Step 4: Commit**

```bash
git add gigaxfer-core/src/test/java/com/gigaxfer/core/store/FaultInjectingNfs.java gigaxfer-core/src/test/java/com/gigaxfer/core/store/FinalizeRecoveryTest.java gigaxfer-core/src/main/java/com/gigaxfer/core/store/WriteHandle.java
git commit -m "test(core): Finalize recovery windows F1b/F2/F2b/F3/F4/F5/F5b and scenario 11"
```

---

### Task 10: 執行器滿與 timeout 下的行為（F18 library 側）

**Files:**
- Test: `gigaxfer-core/src/test/java/com/gigaxfer/core/store/FinalizeUnderPressureTest.java`

**Interfaces:**
- Consumes: `BoundedNfsExecutor`、`LocalStore`、`WriteHandle`。

- [ ] **Step 1: 寫測試**

```java
package com.gigaxfer.core.store;

import com.gigaxfer.core.layout.PathLayout;
import com.gigaxfer.core.nfs.BoundedNfsExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinalizeUnderPressureTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-09-22T08:15:03Z"), ZoneOffset.UTC);

    /** 占滿唯一槽位，回傳釋放用 latch。 */
    static CountDownLatch occupy(BoundedNfsExecutor nfs) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                nfs.call("hang", () -> { started.countDown(); release.await(); return null; });
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        started.await();
        return release;
    }

    @Test
    void beginWrite_when_pool_full_is_unavailable(@TempDir Path root) throws Exception {
        try (BoundedNfsExecutor nfs = new BoundedNfsExecutor("t", 1, Duration.ofMillis(100))) {
            LocalStore store = new LocalStore("P3", new PathLayout(root, ZoneOffset.UTC), nfs, WriteGate.open(), clock);
            CountDownLatch release = occupy(nfs);
            assertThatThrownBy(() -> store.beginWrite("mes", "metrology", "L1"))
                .isInstanceOf(WriteRejectedException.class)
                .satisfies(e -> assertThat(((WriteRejectedException) e).reason()).isEqualTo(WriteRejectedException.Reason.UNAVAILABLE));
            release.countDown();
        }
    }

    @Test
    void write_when_pool_full_throws_io_exception(@TempDir Path root) throws Exception {
        try (BoundedNfsExecutor nfs = new BoundedNfsExecutor("t", 1, Duration.ofMillis(100))) {
            LocalStore store = new LocalStore("P3", new PathLayout(root, ZoneOffset.UTC), nfs, WriteGate.open(), clock);
            WriteHandle h = store.beginWrite("mes", "metrology", "L1");
            CountDownLatch release = occupy(nfs);
            byte[] big = new byte[128 * 1024]; // 超過 64 KB buffer，強制底層 write
            assertThatThrownBy(() -> h.stream().write(big)).isInstanceOf(IOException.class);
            release.countDown();
        }
    }

    @Test
    void finalize_when_pool_full_is_pending_confirmation_and_retry_succeeds(@TempDir Path root) throws Exception {
        try (BoundedNfsExecutor nfs = new BoundedNfsExecutor("t", 1, Duration.ofMillis(100))) {
            LocalStore store = new LocalStore("P3", new PathLayout(root, ZoneOffset.UTC), nfs, WriteGate.open(), clock);
            WriteHandle h = store.beginWrite("mes", "metrology", "L1");
            h.stream().write("v".getBytes(StandardCharsets.UTF_8));
            CountDownLatch release = occupy(nfs);
            FinalizeResult r = h.finalize();
            assertThat(r).isInstanceOf(FinalizeResult.PendingConfirmation.class);
            assertThat(((FinalizeResult.PendingConfirmation) r).op()).isEqualTo("fsync-writing");
            release.countDown();
            Thread.sleep(50);
            assertThat(h.finalize()).isInstanceOf(FinalizeResult.Success.class);
            assertThat(root.resolve("P3/mes/metrology/2026-09-22/08/L1")).hasContent("v");
        }
    }
}
```

- [ ] **Step 2: 跑測試**

Run: `mvn -q -pl gigaxfer-core test -Dtest=FinalizeUnderPressureTest`
Expected: 3 tests passed。

- [ ] **Step 3: 跑全部並 commit**

Run: `mvn -q -pl gigaxfer-core test`
Expected: all passed。

```bash
git add gigaxfer-core/src/test/java/com/gigaxfer/core/store/FinalizeUnderPressureTest.java
git commit -m "test(core): pool exhaustion maps to UNAVAILABLE / IOException / PENDING_CONFIRMATION"
```

---

### Task 11: Application 整合契約文件

**Files:**
- Create: `gigaxfer-core/README.md`

- [ ] **Step 1: 寫 README**

```markdown
# gigaxfer-core

Application 端 Storage Access contract 的核心（無 Spring、無 DB）。Spring Boot starter 見 `gigaxfer-library`（P10）。

## 契約（SR-01、D8、D23、§5）

```java
LocalStore store = new LocalStore("P3", new PathLayout(mountRoot, ZoneId.systemDefault()),
    new BoundedNfsExecutor("app", 16, Duration.ofSeconds(30)), gate, Clock.systemUTC());

WriteHandle h = store.beginWrite("mes", "metrology", "L123-R2");   // 可丟 WriteRejectedException / IllegalArgumentException
h.stream().write(bytes);                                          // 可丟 IOException（NFS 池滿或寫入失敗）→ 視為本次交易失敗
FinalizeResult r = h.finalize();
switch (r) {
    case FinalizeResult.Success s -> commitBusinessTransaction(s.identity());   // 只有這裡可以 commit
    case FinalizeResult.PendingConfirmation p -> retryLater(h);                // 不 commit；重呼 h.finalize() 直到確定
    case FinalizeResult.Failure f -> failTransaction(f.reason(), f.detail());   // CONFLICT / DECLARATION_EXPIRED → 換 Logical key
}
```

規則：
1. Logical key 對不同內容唯一（含 run id / timestamp）；不得以 `.writing`、`.tmp` 結尾或含 `.manifest`。
2. `finalize()` 回 `Success` 才 commit 業務交易；`PendingConfirmation` 重呼同一 handle 的 `finalize()`；`Failure` 視為交易失敗。
3. 交易重跑直接 `beginWrite` + `finalize`，冪等保證不重複；不需先查。
4. `discard()` 只允許在 `finalize()` 之前。
5. `close()` 只關通道、不刪檔；`PendingConfirmation` 後不要 `close()` 再重試（通道已由 ① 關閉則無影響）。
6. 暫存寫入超過 24 h 未 Finalize 可能被清道夫中止；宣告後超過 7 天未發布的 key 不可再發布（`DECLARATION_EXPIRED`）。

## NFS 佈局

```
<root>/<source>/<ns>/<class>/<yyyy-MM-dd>/<HH>/<key>              正式內容（存在 = Ready）
<root>/<source>/<ns>/<class>/<yyyy-MM-dd>/<HH>/<key>.<uuid>.writing 暫存
<root>/<source>/<ns>/.manifest/<bucket>/<key>.manifest             Source Ready 權威紀錄
<root>/<source>/<ns>/.manifest/<bucket>/<key>.manifest.<uuid>.tmp  暫存
```

## 測試

`mvn -q -pl gigaxfer-core test`。故障窗口對照 `docs/design/system-design.md` §6：F1b、F2、F2b、F3、F4、F5、F5b、F18（library 側）。
```

- [ ] **Step 2: Commit**

```bash
git add gigaxfer-core/README.md
git commit -m "docs(core): Application integration contract"
```

---

## 設計偏差（實作時決定，需回填 design-decisions.md）

1. **manifest 目錄加 `.manifest/` 前綴**：D48 寫 `<source>/<ns>/<bucket>/`，與 `<source>/<ns>/<class>/` 同層，Data class 若恰為 3 個 hex 字元會撞名。改為 `<source>/<ns>/.manifest/<bucket>/`；Data class 不得以 `.` 開頭已由 FileIdentity 規則涵蓋 namespace / key，Data class 的同一規則由 P10 的 Policy 登錄檢查執行。
2. **PathLayout 的時區為建構參數**：D2 只說 Source 時鐘；目錄名用哪個時區需明定。預設 Node 本地時區（ops 用 `ls` 找「今天」直覺），參與同步的所有 Node 必須使用同一時區設定。
3. **Logical key 保留字尾**：`.writing`、`.tmp` 結尾與含 `.manifest` 一律拒絕，寫進 SR-01 Application 義務。
4. **write() 經有界執行器**：D51 說每個 NFS 操作經執行器；串流寫入以 64 KB buffer 聚合後每次底層 write 各占一次槽位。若壓測顯示槽位被大檔寫入長期占滿，改為 write 走專用 pool（純加法）。

## Self-review 紀錄

- **Spec 覆蓋**：§2.2 ①–④ → Task 8；EEXIST 四項比對 / rediscovery / 年齡 / link 順序（D53 修）→ Task 8 + Task 9；D44 tmp + link → Task 8 + F4 測試；D48 修 content_path 取宣告時刻 → Task 8（`declaredAt`）+ Task 9 跨小時測試；D51 有界執行器 → Task 6 + Task 10；D51 修 2 → F1b 測試；F3 → Task 9；`<key>` 存在 = Ready 兩端一致 → PathLayout 相對路徑 + 不寫 Target 邏輯（P05）。未涵蓋且刻意留給後續計畫：掃描 rediscovery 分類（P03）、Abandoned TTL 清理（P09）、Policy / statfs 閘門實作（P10）、`/locate`（P10）。
- **Placeholder 掃描**：無 TBD / TODO；每個 code step 皆有完整程式碼。
- **型別一致**：`FinalizeResult.Success(identity, contentPath)`、`Failure(reason, detail)`、`PendingConfirmation(op, detail)`、`FailureReason.{CONFLICT, DECLARATION_EXPIRED, IO}`、`WriteRejectedException.Reason.{REJECTED, UNAVAILABLE, IO}`、`NfsExecutor.call/run`、`BoundedNfsExecutor(name, slots, timeout).inUse()`、`PathLayout(root, zone)`、`LocalStore(sourceNode, layout, nfs, gate, clock)`、`WriteHandle.stream()/finalize()/discard()/close()/writingPath()/uuid()` 在 Task 7–11 一致。`WriteHandle.uuid()` 為 package-private，測試同 package 可用。
