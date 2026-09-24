package com.gigaxfer.sync.db.migration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Schema V1（system-design §3）。SQL 在 {@value #SCRIPT}，本類別逐句執行，<b>已存在的表／索引跳過</b>。
 *
 * <p>Oracle（與 H2）的 DDL 隱式 commit、無法回滾：V1 中途斷線會留下半套物件，Flyway 記一筆失敗紀錄，
 * 或連線已斷而連紀錄都沒寫。逐句冪等讓 {@link com.gigaxfer.sync.db.DbBootstrap} 移除失敗紀錄後重跑 V1
 * 即從斷點接續（P02-08、D34 修），不刪任何既存物件或資料。
 *
 * <p>ponytail: 以名稱判定「已存在」，不比對欄位；同一 schema 裡若已有同名但不同形狀的表會被沿用。
 * 部署前提是專用 schema（README「資料庫」節）。
 */
public class V1__schema extends BaseJavaMigration {
    static final String SCRIPT = "db/schema/V1.sql";
    private static final Pattern CREATE = Pattern.compile(
        "^CREATE\\s+(?:UNIQUE\\s+)?(TABLE|INDEX)\\s+(\\w+)(?:\\s+ON\\s+(\\w+))?", Pattern.CASE_INSENSITIVE);

    /** 以 SQL 內容的 CRC32 當 checksum：已套用後 SQL 被改，Flyway validate 會擋下。 */
    @Override
    public Integer getChecksum() {
        CRC32 crc = new CRC32();
        crc.update(script());
        return (int) crc.getValue();
    }

    @Override
    public void migrate(Context context) throws SQLException {
        Connection c = context.getConnection();
        for (String sql : statements(new String(script(), StandardCharsets.UTF_8))) {
            Matcher m = CREATE.matcher(sql);
            if (!m.find()) {
                throw new IllegalStateException("V1 script may only contain CREATE TABLE / CREATE INDEX: " + sql);
            }
            boolean isTable = m.group(1).equalsIgnoreCase("TABLE");
            if (isTable ? tableExists(c, m.group(2)) : indexExists(c, m.group(3), m.group(2))) {
                continue;
            }
            try (Statement s = c.createStatement()) {
                s.execute(sql);
            }
        }
    }

    /** 去掉整行 `--` 註解後以分號切句；腳本內不得有字串或註解含分號。 */
    static List<String> statements(String script) {
        StringBuilder b = new StringBuilder();
        for (String line : script.split("\n")) {
            if (!line.strip().startsWith("--")) {
                b.append(line).append('\n');
            }
        }
        List<String> out = new ArrayList<>();
        for (String part : b.toString().split(";")) {
            String t = part.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static byte[] script() {
        try (InputStream in = V1__schema.class.getClassLoader().getResourceAsStream(SCRIPT)) {
            if (in == null) {
                throw new IllegalStateException(SCRIPT + " not on classpath");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean tableExists(Connection c, String name) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        for (String n : variants(name)) {
            try (ResultSet rs = md.getTables(c.getCatalog(), c.getSchema(), escape(md, n), new String[] {"TABLE"})) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean indexExists(Connection c, String table, String index) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        for (String t : variants(table)) {
            // approximate=true：Oracle 驅動不為此跑 ANALYZE
            try (ResultSet rs = md.getIndexInfo(c.getCatalog(), c.getSchema(), t, false, true)) {
                while (rs.next()) {
                    if (index.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 識別字大小寫依 DB 而異（Oracle 轉大寫；H2 DATABASE_TO_UPPER=false 保留原樣）。 */
    private static List<String> variants(String n) {
        return List.of(n, n.toUpperCase(Locale.ROOT), n.toLowerCase(Locale.ROOT)).stream().distinct().toList();
    }

    /** getTables 的名稱是 LIKE 樣式：`_` 要跳脫，否則 file_identity 會匹配 fileXidentity。 */
    private static String escape(DatabaseMetaData md, String n) throws SQLException {
        String esc = md.getSearchStringEscape();
        return esc == null || esc.isEmpty() ? n : n.replace("_", esc + "_").replace("%", esc + "%");
    }
}
