package com.binlog.analyzer.model;

import java.util.ArrayList;
import java.util.List;

public class JobView {

    public String id;
    public String status;
    public String phase;
    public int progress;
    public long eventsRead;
    public long matchedRows;
    public String currentFile;
    public String error;
    public String stopReason;
    public boolean truncated;
    public List<String> warnings = new ArrayList<>();
    public long startedAt;
    public long finishedAt;
    public long elapsedMs;
    public String schema;
    public String table;
    public boolean online;
    public String charset;
    public int totalEntries;
    public List<SqlEntry> entries;
}
