package com.binlog.analyzer.model;

public class TableColumn {

    public String name;
    public String type;
    public String collation;
    public boolean nullable;
    public boolean primaryKey;
    public int ordinal;

    public TableColumn() {
    }

    public TableColumn(String name, String type, String collation, boolean nullable, boolean primaryKey, int ordinal) {
        this.name = name;
        this.type = type;
        this.collation = collation;
        this.nullable = nullable;
        this.primaryKey = primaryKey;
        this.ordinal = ordinal;
    }

    public String baseType() {
        if (type == null) {
            return "";
        }
        String lower = type.trim().toLowerCase();
        int parenthesis = lower.indexOf('(');
        if (parenthesis >= 0) {
            lower = lower.substring(0, parenthesis);
        }
        int space = lower.indexOf(' ');
        if (space >= 0) {
            lower = lower.substring(0, space);
        }
        return lower;
    }
}
