package com.binlog.analyzer.service;

import com.binlog.analyzer.model.TableColumn;
import com.github.shyiko.mysql.binlog.event.deserialization.json.JsonBinary;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SqlValueFormatter {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Map<String, List<String>> ENUM_SET_CACHE = new ConcurrentHashMap<>();

    private SqlValueFormatter() {
    }

    public static String literal(Object value, TableColumn column, Charset charset) throws SqlBuildException {
        if (value == null) {
            return "NULL";
        }
        String baseType = column == null ? "" : column.baseType();

        if ("enum".equals(baseType) && value instanceof Number) {
            List<String> labels = labels(column.type);
            int index = ((Number) value).intValue();
            if (index >= 1 && index <= labels.size()) {
                return quote(labels.get(index - 1));
            }
        }
        if ("set".equals(baseType) && value instanceof Number) {
            List<String> labels = labels(column.type);
            long mask = ((Number) value).longValue();
            List<String> selected = new ArrayList<>();
            for (int i = 0; i < labels.size(); i++) {
                if ((mask & (1L << i)) != 0) {
                    selected.add(labels.get(i));
                }
            }
            return quote(String.join(",", selected));
        }

        if (value instanceof String) {
            return quote((String) value);
        }
        if (value instanceof byte[]) {
            return binaryLiteral((byte[]) value, baseType, charset);
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "1" : "0";
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        if (value instanceof BigInteger) {
            return value.toString();
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double) {
            if (isTemporalLong(value, baseType)) {
                return temporalLiteral(((Number) value).longValue(), baseType);
            }
            return value.toString();
        }
        if (value instanceof BitSet) {
            return Long.toString(bitsToLong((BitSet) value));
        }
        if (value instanceof Timestamp) {
            return quote(formatDateTime(((Timestamp) value).toLocalDateTime()));
        }
        if (value instanceof Date) {
            return quote(DATE.format(((Date) value).toLocalDate()));
        }
        if (value instanceof Time) {
            return quote(((Time) value).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        }
        if (value instanceof java.util.Date) {
            LocalDateTime dateTime = LocalDateTime.ofInstant(((java.util.Date) value).toInstant(), ZoneId.systemDefault());
            return quote(formatDateTime(dateTime));
        }
        if (value instanceof LocalDateTime) {
            return quote(formatDateTime((LocalDateTime) value));
        }
        if (value instanceof LocalDate) {
            return quote(DATE.format((LocalDate) value));
        }
        if (value instanceof LocalTime) {
            return quote(((LocalTime) value).format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        }
        return quote(value.toString());
    }

    public static String identifier(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    public static String qualifiedName(String schema, String table) {
        return identifier(schema) + "." + identifier(table);
    }

    public static String quote(String text) {
        StringBuilder builder = new StringBuilder(text.length() + 2);
        builder.append('\'');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\0':
                    builder.append("\\0");
                    break;
                case '\'':
                    builder.append("\\'");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case 26:
                    builder.append("\\Z");
                    break;
                default:
                    builder.append(c);
            }
        }
        builder.append('\'');
        return builder.toString();
    }

    private static boolean isTemporalLong(Object value, String baseType) {
        return value instanceof Long && isTemporalType(baseType);
    }

    private static boolean isTemporalType(String baseType) {
        return "date".equals(baseType) || "datetime".equals(baseType) || "timestamp".equals(baseType)
                || "time".equals(baseType) || "year".equals(baseType);
    }

    private static String temporalLiteral(long micros, String baseType) {
        if ("year".equals(baseType)) {
            return Long.toString(1900 + micros);
        }
        if ("time".equals(baseType)) {
            boolean negative = micros < 0;
            long absolute = negative ? -micros : micros;
            long totalSeconds = absolute / 1_000_000L;
            long fraction = absolute % 1_000_000L;
            long hours = totalSeconds / 3600L;
            long minutes = (totalSeconds % 3600L) / 60L;
            long seconds = totalSeconds % 60L;
            StringBuilder text = new StringBuilder();
            if (negative) {
                text.append('-');
            }
            text.append(String.format("%02d:%02d:%02d", hours, minutes, seconds));
            if (fraction > 0) {
                text.append('.').append(String.format("%06d", fraction).replaceAll("0+$", ""));
            }
            return quote(text.toString());
        }

        long seconds = Math.floorDiv(micros, 1_000_000L);
        long fractionMicros = Math.floorMod(micros, 1_000_000L);
        if ("date".equals(baseType)) {
            LocalDate date = LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC).toLocalDate();
            return quote(DATE.format(date));
        }
        if ("timestamp".equals(baseType)) {
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(seconds, fractionMicros * 1000L), ZoneId.systemDefault());
            return quote(formatDateTime(dateTime, fractionMicros));
        }
        LocalDateTime dateTime = LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC);
        return quote(formatDateTime(dateTime, fractionMicros));
    }

    private static String formatDateTime(LocalDateTime dateTime) {
        return DATE_TIME.format(dateTime);
    }

    private static String formatDateTime(LocalDateTime dateTime, long fractionMicros) {
        String base = DATE_TIME.format(dateTime);
        if (fractionMicros <= 0) {
            return base;
        }
        String fraction = String.format("%06d", fractionMicros).replaceAll("0+$", "");
        return base + "." + fraction;
    }

    private static String binaryLiteral(byte[] bytes, String baseType, Charset charset) throws SqlBuildException {
        if ("json".equals(baseType)) {
            try {
                return quote(JsonBinary.parseAsString(bytes));
            } catch (IOException e) {
                throw new SqlBuildException("JSON 字段解析失败: " + e.getMessage(), e);
            }
        }
        if (isGeometry(baseType)) {
            if (bytes.length >= 4) {
                int srid = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                byte[] wkb = new byte[bytes.length - 4];
                System.arraycopy(bytes, 4, wkb, 0, wkb.length);
                return "ST_GeomFromWKB(" + hex(wkb) + ", " + srid + ")";
            }
            return "ST_GeomFromWKB(" + hex(bytes) + ")";
        }
        if (isTextType(baseType)) {
            return quote(new String(bytes, charset));
        }
        return hex(bytes);
    }

    private static boolean isTextType(String baseType) {
        return "char".equals(baseType) || "varchar".equals(baseType)
                || "tinytext".equals(baseType) || "text".equals(baseType)
                || "mediumtext".equals(baseType) || "longtext".equals(baseType);
    }

    private static boolean isGeometry(String baseType) {
        return "geometry".equals(baseType) || "point".equals(baseType) || "linestring".equals(baseType)
                || "polygon".equals(baseType) || "multipoint".equals(baseType)
                || "multilinestring".equals(baseType) || "multipolygon".equals(baseType)
                || "geometrycollection".equals(baseType) || "geomcollection".equals(baseType);
    }

    private static String hex(byte[] bytes) {
        if (bytes.length == 0) {
            return "''";
        }
        StringBuilder builder = new StringBuilder(bytes.length * 2 + 2).append("0x");
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    private static long bitsToLong(BitSet bits) {
        long value = 0;
        for (int i = bits.nextSetBit(0); i >= 0 && i < 64; i = bits.nextSetBit(i + 1)) {
            value |= (1L << i);
        }
        return value;
    }

    static List<String> labels(String typeDefinition) throws SqlBuildException {
        if (typeDefinition == null) {
            return new ArrayList<>();
        }
        List<String> cached = ENUM_SET_CACHE.get(typeDefinition);
        if (cached != null) {
            return cached;
        }
        int open = typeDefinition.indexOf('(');
        int close = typeDefinition.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return new ArrayList<>();
        }
        String inside = typeDefinition.substring(open + 1, close);
        List<String> labels = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < inside.length(); i++) {
            char c = inside.charAt(i);
            if (quoted) {
                if (c == '\'') {
                    if (i + 1 < inside.length() && inside.charAt(i + 1) == '\'') {
                        current.append('\'');
                        i++;
                    } else {
                        quoted = false;
                        labels.add(current.toString());
                        current.setLength(0);
                    }
                } else if (c == '\\' && i + 1 < inside.length()) {
                    current.append(inside.charAt(++i));
                } else {
                    current.append(c);
                }
            } else if (c == '\'') {
                quoted = true;
            }
        }
        List<String> result = new ArrayList<>(labels);
        ENUM_SET_CACHE.put(typeDefinition, result);
        return result;
    }
}
