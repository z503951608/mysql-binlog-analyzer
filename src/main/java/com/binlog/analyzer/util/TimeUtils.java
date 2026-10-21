package com.binlog.analyzer.util;

import com.binlog.analyzer.web.ApiException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public final class TimeUtils {

    public static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter DETAIL_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss");
    public static final DateTimeFormatter FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final List<DateTimeFormatter> INPUT_FORMATS = new ArrayList<>();

    static {
        INPUT_FORMATS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        INPUT_FORMATS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
        INPUT_FORMATS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        INPUT_FORMATS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        INPUT_FORMATS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"));
        INPUT_FORMATS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
    }

    private TimeUtils() {
    }

    public static Long parseDateTime(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String value = text.trim();
        for (DateTimeFormatter format : INPUT_FORMATS) {
            try {
                LocalDateTime dateTime = LocalDateTime.parse(value, format);
                return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // try next pattern
            }
        }
        try {
            LocalDate date = LocalDate.parse(value);
            return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "时间格式不正确: " + text + "（应为 yyyy-MM-dd HH:mm:ss）");
        }
    }

    public static String formatDisplay(long epochMillis) {
        return DISPLAY_FORMAT.format(LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()));
    }

    public static String formatDetail(long epochMillis) {
        return DETAIL_FORMAT.format(LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()));
    }

    public static String formatFileTime(long epochMillis) {
        return FILE_FORMAT.format(LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()));
    }
}
