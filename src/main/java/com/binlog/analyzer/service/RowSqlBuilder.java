package com.binlog.analyzer.service;

import com.binlog.analyzer.model.TableColumn;
import com.binlog.analyzer.model.TableMeta;

import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class RowSqlBuilder {

    private final Charset charset;
    private final Consumer<String> warningSink;

    public RowSqlBuilder(Charset charset, Consumer<String> warningSink) {
        this.charset = charset;
        this.warningSink = warningSink;
    }

    public SqlPair forInsert(TableMeta table, Serializable[] row, BitSet included, int totalColumns)
            throws SqlBuildException {
        List<ColumnValue> values = values(table, row, included, totalColumns);
        if (values.isEmpty()) {
            throw new SqlBuildException("INSERT 事件中没有任何列数据");
        }
        String insert = insertSql(table, values);
        String delete = deleteSql(table, values);
        return new SqlPair(insert, delete);
    }

    public SqlPair forDelete(TableMeta table, Serializable[] row, BitSet included, int totalColumns)
            throws SqlBuildException {
        List<ColumnValue> values = values(table, row, included, totalColumns);
        if (values.isEmpty()) {
            throw new SqlBuildException("DELETE 事件中没有任何列数据");
        }
        String delete = deleteSql(table, values);
        String insert = insertSql(table, values);
        return new SqlPair(delete, insert);
    }

    public SqlPair forUpdate(TableMeta table,
                             Serializable[] before, BitSet includedBefore,
                             Serializable[] after, BitSet includedAfter,
                             int totalColumns) throws SqlBuildException {
        List<ColumnValue> beforeValues = values(table, before, includedBefore, totalColumns);
        List<ColumnValue> afterValues = values(table, after, includedAfter, totalColumns);
        if (beforeValues.isEmpty() || afterValues.isEmpty()) {
            throw new SqlBuildException("UPDATE 事件缺少变更前后的列数据");
        }
        String redo = "UPDATE " + SqlValueFormatter.qualifiedName(table.schema, table.table)
                + " SET " + assignments(afterValues)
                + " WHERE " + whereClause(table, beforeValues) + ";";
        String undo = "UPDATE " + SqlValueFormatter.qualifiedName(table.schema, table.table)
                + " SET " + assignments(beforeValues)
                + " WHERE " + whereClause(table, afterValues) + ";";
        return new SqlPair(redo, undo);
    }

    private String insertSql(TableMeta table, List<ColumnValue> values) throws SqlBuildException {
        StringBuilder sql = new StringBuilder("INSERT INTO ")
                .append(SqlValueFormatter.qualifiedName(table.schema, table.table))
                .append(" (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(SqlValueFormatter.identifier(values.get(i).column.name));
        }
        sql.append(") VALUES (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(SqlValueFormatter.literal(values.get(i).value, values.get(i).column, charset));
        }
        return sql.append(");").toString();
    }

    private String deleteSql(TableMeta table, List<ColumnValue> values) throws SqlBuildException {
        return "DELETE FROM " + SqlValueFormatter.qualifiedName(table.schema, table.table)
                + " WHERE " + whereClause(table, values) + ";";
    }

    private String assignments(List<ColumnValue> values) throws SqlBuildException {
        StringBuilder set = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                set.append(", ");
            }
            ColumnValue value = values.get(i);
            set.append(SqlValueFormatter.identifier(value.column.name))
                    .append(" = ")
                    .append(SqlValueFormatter.literal(value.value, value.column, charset));
        }
        return set.toString();
    }

    private String whereClause(TableMeta table, List<ColumnValue> values) throws SqlBuildException {
        Map<String, ColumnValue> byName = new HashMap<>();
        for (ColumnValue value : values) {
            byName.put(value.column.name, value);
        }

        List<ColumnValue> chosen;
        if (!table.primaryKeys.isEmpty() && byName.keySet().containsAll(table.primaryKeys)) {
            chosen = new ArrayList<>();
            for (String key : table.primaryKeys) {
                chosen.add(byName.get(key));
            }
        } else {
            if (!table.primaryKeys.isEmpty()) {
                warningSink.accept("表 " + table.key() + " 的 " + "UPDATE/DELETE 记录缺少主键镜像，已改用全部列生成 WHERE 条件，请执行前核对");
            } else {
                warningSink.accept("表 " + table.key() + " 未定义主键，WHERE 条件使用全部记录列生成，请执行前核对");
            }
            chosen = values;
        }

        StringBuilder where = new StringBuilder();
        for (int i = 0; i < chosen.size(); i++) {
            if (i > 0) {
                where.append(" AND ");
            }
            ColumnValue value = chosen.get(i);
            where.append(SqlValueFormatter.identifier(value.column.name));
            if (value.value == null) {
                where.append(" IS NULL");
            } else {
                where.append(" = ").append(SqlValueFormatter.literal(value.value, value.column, charset));
            }
        }
        return where.toString();
    }

    private List<ColumnValue> values(TableMeta table, Serializable[] row, BitSet included, int totalColumns)
            throws SqlBuildException {
        if (included == null) {
            throw new SqlBuildException("行事件缺少列位图，无法确定列位置");
        }
        if (included.length() > totalColumns) {
            throw new SqlBuildException("binlog 行事件的列数超过表结构列数，已跳过");
        }
        List<Integer> indexes = new ArrayList<>();
        for (int i = included.nextSetBit(0); i >= 0; i = included.nextSetBit(i + 1)) {
            indexes.add(i);
        }
        if (row == null || row.length != indexes.size()) {
            throw new SqlBuildException("binlog 行数据的列数与列位图不一致，已跳过");
        }
        List<ColumnValue> result = new ArrayList<>(indexes.size());
        for (int position = 0; position < indexes.size(); position++) {
            int columnIndex = indexes.get(position);
            TableColumn column = table.column(columnIndex);
            if (column == null) {
                throw new SqlBuildException("数据字典中缺少第 " + (columnIndex + 1)
                        + " 列的定义，请刷新数据字典后重试");
            }
            result.add(new ColumnValue(column, row[position]));
        }
        return result;
    }

    private static final class ColumnValue {
        private final TableColumn column;
        private final Object value;

        private ColumnValue(TableColumn column, Object value) {
            this.column = column;
            this.value = value;
        }
    }
}
