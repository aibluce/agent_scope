package com.ll.agent.text2sql.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/** 把 JDBC/MyBatis 返回的值转换成可以直接 JSON 序列化的形式。 */
public final class ValueNormalizer {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ValueNormalizer() {}

    public static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().format(TS_FMT);
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.format(TS_FMT);
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (value instanceof LocalDate ld) {
            return ld.toString();
        }
        if (value instanceof java.sql.Time t) {
            return t.toLocalTime().toString();
        }
        if (value instanceof LocalTime lt) {
            return lt.toString();
        }
        if (value instanceof BigDecimal bd) {
            // 金额类统一保留两位小数，避免 JSON 里出现 123.4 / 123.4000000001
            return bd.setScale(Math.max(bd.scale(), 2), RoundingMode.HALF_UP);
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof Boolean || value instanceof Number || value instanceof String) {
            return value;
        }
        return value.toString();
    }
}
