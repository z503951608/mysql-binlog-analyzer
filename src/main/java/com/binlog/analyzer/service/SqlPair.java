package com.binlog.analyzer.service;

public class SqlPair {

    public final String redo;
    public final String undo;

    public SqlPair(String redo, String undo) {
        this.redo = redo;
        this.undo = undo;
    }
}
