package com.binlog.analyzer.model;

public class AnalyzeRequest {

    public boolean online = true;
    public String schema;
    public String table;
    public String startFile;
    public boolean analyzeToLatest = false;
    public String startTime;
    public String endTime;
    public String operation = "all";
    public String keyword = "";
    public String display = "all";
    public String charset = "utf8";
    public String uploadId;
}
