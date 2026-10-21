package com.binlog.analyzer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "binlog")
public class BinlogProperties {

    private String uploadDir = "./data/binlogs";
    private int maxResults = 20000;
    private long idleTimeoutMs = 3000;
    private String mysqlbinlogPath = "";
    private long clientServerId = 20200621;
    private int jobRetentionMinutes = 120;
    private int downloadTimeoutSeconds = 300;

    public String getUploadDir() {
        return uploadDir;
    }

    public void setUploadDir(String uploadDir) {
        this.uploadDir = uploadDir;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }

    public long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    public void setIdleTimeoutMs(long idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
    }

    public String getMysqlbinlogPath() {
        return mysqlbinlogPath;
    }

    public void setMysqlbinlogPath(String mysqlbinlogPath) {
        this.mysqlbinlogPath = mysqlbinlogPath;
    }

    public long getClientServerId() {
        return clientServerId;
    }

    public void setClientServerId(long clientServerId) {
        this.clientServerId = clientServerId;
    }

    public int getJobRetentionMinutes() {
        return jobRetentionMinutes;
    }

    public void setJobRetentionMinutes(int jobRetentionMinutes) {
        this.jobRetentionMinutes = jobRetentionMinutes;
    }

    public int getDownloadTimeoutSeconds() {
        return downloadTimeoutSeconds;
    }

    public void setDownloadTimeoutSeconds(int downloadTimeoutSeconds) {
        this.downloadTimeoutSeconds = downloadTimeoutSeconds;
    }
}
