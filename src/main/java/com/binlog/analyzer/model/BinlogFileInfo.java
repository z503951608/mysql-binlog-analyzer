package com.binlog.analyzer.model;

public class BinlogFileInfo {

    public String name;
    public long size;
    public boolean encrypted;

    public BinlogFileInfo() {
    }

    public BinlogFileInfo(String name, long size, boolean encrypted) {
        this.name = name;
        this.size = size;
        this.encrypted = encrypted;
    }
}
