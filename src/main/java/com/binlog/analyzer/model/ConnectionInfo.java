package com.binlog.analyzer.model;

import java.util.ArrayList;
import java.util.List;

public class ConnectionInfo {

    public boolean connected;
    public String host;
    public int port;
    public String user;
    public String serverVersion;
    public List<String> schemas = new ArrayList<>();
}
