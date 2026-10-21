package com.binlog.analyzer.service;

public class SqlBuildException extends Exception {

    private static final long serialVersionUID = 1L;

    public SqlBuildException(String message) {
        super(message);
    }

    public SqlBuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
