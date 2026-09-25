package com.gigaxfer.sync.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.TimeZone;
import org.springframework.jdbc.core.SqlTypeValue;

/**
 * DB 時間欄位一律以 UTC 讀寫（D58 ⑦）：不依賴 JVM 或 DB session 的預設時區。
 * 欄位是無時區的 {@code TIMESTAMP}，存的是 UTC 牆上時間；JVM 預設時區有夏令時間時，
 * 不帶 Calendar 的 setTimestamp／getTimestamp 會把不存在或重複的本地時刻轉錯。
 */
public final class DbTime {
    private DbTime() {}

    /** 每次新建：Calendar 不是執行緒安全的。 */
    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone(ZoneOffset.UTC));
    }

    /** JdbcTemplate 參數：以 UTC 寫入，null 寫成 SQL NULL。 */
    public static SqlTypeValue utc(Instant t) {
        return (ps, index, sqlType, typeName) -> {
            if (t == null) {
                ps.setNull(index, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(index, Timestamp.from(t), utc());
            }
        };
    }

    /** 以 UTC 讀回；SQL NULL 回 null。 */
    public static Instant read(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column, utc());
        return ts == null ? null : ts.toInstant();
    }
}
