package com.binlog.analyzer.util;

import com.binlog.analyzer.web.ApiException;
import org.springframework.http.HttpStatus;

import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Charsets {

    private static final Map<String, Charset> SUPPORTED = new LinkedHashMap<>();

    static {
        SUPPORTED.put("utf8", Charset.forName("UTF-8"));
        SUPPORTED.put("utf8mb4", Charset.forName("UTF-8"));
        SUPPORTED.put("gbk", Charset.forName("GBK"));
        SUPPORTED.put("gb18030", Charset.forName("GB18030"));
        SUPPORTED.put("big5", Charset.forName("Big5"));
        SUPPORTED.put("latin1", Charset.forName("ISO-8859-1"));
    }

    private Charsets() {
    }

    public static Charset resolve(String name) {
        if (name == null || name.trim().isEmpty()) {
            return SUPPORTED.get("utf8");
        }
        Charset charset = SUPPORTED.get(name.trim().toLowerCase());
        if (charset == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "不支持的字符集: " + name);
        }
        return charset;
    }

    public static String canonicalName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "utf8";
        }
        String key = name.trim().toLowerCase();
        if ("utf8mb4".equals(key)) {
            return "utf8";
        }
        if (!SUPPORTED.containsKey(key)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "不支持的字符集: " + name);
        }
        return key;
    }

    public static Map<String, Charset> supported() {
        return SUPPORTED;
    }
}
