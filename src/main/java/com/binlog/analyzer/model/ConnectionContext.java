package com.binlog.analyzer.model;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

public class ConnectionContext implements Serializable {

    private static final long serialVersionUID = 1L;

    public final String host;
    public final int port;
    public final String user;
    public final String password;
    public final String serverVersion;
    public final List<String> schemas;

    public ConnectionContext(String host, int port, String user, String password,
                             String serverVersion, List<String> schemas) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.serverVersion = serverVersion;
        this.schemas = Collections.unmodifiableList(new ArrayList<>(schemas));
    }

    public String jdbcUrl() {
        String zone;
        try {
            zone = URLEncoder.encode(TimeZone.getDefault().getID(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            zone = "UTC";
        }
        return "jdbc:mysql://" + host + ":" + port + "/"
                + "?useUnicode=true&characterEncoding=UTF-8"
                + "&sslMode=PREFERRED&allowPublicKeyRetrieval=true"
                + "&connectTimeout=8000&socketTimeout=120000"
                + "&serverTimezone=" + zone
                + "&zeroDateTimeBehavior=CONVERT_TO_NULL"
                + "&tinyInt1isBit=false";
    }

    public ConnectionInfo toInfo() {
        ConnectionInfo info = new ConnectionInfo();
        info.connected = true;
        info.host = host;
        info.port = port;
        info.user = user;
        info.serverVersion = serverVersion;
        info.schemas = new ArrayList<>(schemas);
        return info;
    }
}
