package com.binlog.analyzer.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class TableMeta {

    public String schema;
    public String table;
    public List<TableColumn> columns = new ArrayList<>();
    public Set<String> primaryKeys = new LinkedHashSet<>();

    public String key() {
        return schema + "." + table;
    }

    public TableColumn column(int index) {
        if (index < 0 || index >= columns.size()) {
            return null;
        }
        return columns.get(index);
    }
}
