package com.binlog.analyzer.service;

import com.binlog.analyzer.model.AnalyzeRequest;
import com.binlog.analyzer.model.SqlEntry;
import com.binlog.analyzer.model.TableMeta;
import com.binlog.analyzer.util.Charsets;
import com.github.shyiko.mysql.binlog.event.DeleteRowsEventData;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventData;
import com.github.shyiko.mysql.binlog.event.EventHeader;
import com.github.shyiko.mysql.binlog.event.RotateEventData;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class BinlogEventEngine {

    public enum ProcessResult {
        CONTINUE,
        STOP_END_TIME,
        STOP_LIMIT
    }

    private final AnalysisJob job;
    private final AnalyzeRequest request;
    private final TableMetaResolver metaResolver;
    private final RowSqlBuilder sqlBuilder;
    private final Map<Long, TableInfo> tableMap = new HashMap<>();
    private final String keyword;

    public BinlogEventEngine(AnalysisJob job, TableMetaResolver metaResolver) {
        this.job = job;
        this.request = job.request;
        this.metaResolver = metaResolver;
        this.sqlBuilder = new RowSqlBuilder(Charsets.resolve(request.charset), job::warnOnce);
        String kw = request.keyword == null ? "" : request.keyword.trim();
        this.keyword = kw.isEmpty() ? null : kw.toLowerCase(Locale.ROOT);
    }

    public ProcessResult process(Event event) {
        if (event == null) {
            return ProcessResult.CONTINUE;
        }
        job.incrementEvents();
        EventData data = event.getData();
        if (data == null) {
            return ProcessResult.CONTINUE;
        }
        if (data instanceof RotateEventData) {
            tableMap.clear();
            return ProcessResult.CONTINUE;
        }
        if (data instanceof TableMapEventData) {
            TableMapEventData map = (TableMapEventData) data;
            if (map.getDatabase() != null && map.getTable() != null) {
                int columnCount = map.getColumnTypes() == null ? 0 : map.getColumnTypes().length;
                tableMap.put(map.getTableId(), new TableInfo(map.getDatabase(), map.getTable(), columnCount));
            }
            return ProcessResult.CONTINUE;
        }

        String operation;
        if (data instanceof WriteRowsEventData) {
            operation = "insert";
        } else if (data instanceof UpdateRowsEventData) {
            operation = "update";
        } else if (data instanceof DeleteRowsEventData) {
            operation = "delete";
        } else {
            return ProcessResult.CONTINUE;
        }

        long timestamp = timestamp(event.getHeader());
        if (job.endTimeMillis != null && timestamp > job.endTimeMillis) {
            return ProcessResult.STOP_END_TIME;
        }
        if (job.startTimeMillis != null && timestamp < job.startTimeMillis) {
            return ProcessResult.CONTINUE;
        }
        if (!"all".equals(request.operation) && !request.operation.equals(operation)) {
            return ProcessResult.CONTINUE;
        }

        long tableId;
        if (data instanceof WriteRowsEventData) {
            tableId = ((WriteRowsEventData) data).getTableId();
        } else if (data instanceof UpdateRowsEventData) {
            tableId = ((UpdateRowsEventData) data).getTableId();
        } else {
            tableId = ((DeleteRowsEventData) data).getTableId();
        }

        TableInfo table = tableMap.get(tableId);
        if (table == null) {
            job.warnOnce("未找到 table_id=" + tableId + " 的 TableMap 事件，可能从日志中间开始分析，已跳过相关记录");
            return ProcessResult.CONTINUE;
        }
        if (!matchesSchema(table)) {
            return ProcessResult.CONTINUE;
        }

        TableMeta meta = metaResolver.resolve(table.schema, table.table, table.columnCount);
        if (meta == null) {
            return ProcessResult.CONTINUE;
        }

        if (data instanceof WriteRowsEventData) {
            return processWrite((WriteRowsEventData) data, table, meta, timestamp);
        }
        if (data instanceof UpdateRowsEventData) {
            return processUpdate((UpdateRowsEventData) data, table, meta, timestamp);
        }
        return processDelete((DeleteRowsEventData) data, table, meta, timestamp);
    }

    private ProcessResult processWrite(WriteRowsEventData data, TableInfo table, TableMeta meta, long timestamp) {
        for (Serializable[] row : data.getRows()) {
            try {
                SqlPair pair = sqlBuilder.forInsert(meta, row, data.getIncludedColumns(), table.columnCount);
                if (!addEntry("INSERT", table, timestamp, pair)) {
                    return ProcessResult.STOP_LIMIT;
                }
            } catch (SqlBuildException e) {
                job.warnOnce("表 " + table.key() + " 的 INSERT 记录已跳过: " + e.getMessage());
            }
        }
        return ProcessResult.CONTINUE;
    }

    private ProcessResult processDelete(DeleteRowsEventData data, TableInfo table, TableMeta meta, long timestamp) {
        for (Serializable[] row : data.getRows()) {
            try {
                SqlPair pair = sqlBuilder.forDelete(meta, row, data.getIncludedColumns(), table.columnCount);
                if (!addEntry("DELETE", table, timestamp, pair)) {
                    return ProcessResult.STOP_LIMIT;
                }
            } catch (SqlBuildException e) {
                job.warnOnce("表 " + table.key() + " 的 DELETE 记录已跳过: " + e.getMessage());
            }
        }
        return ProcessResult.CONTINUE;
    }

    private ProcessResult processUpdate(UpdateRowsEventData data, TableInfo table, TableMeta meta, long timestamp) {
        for (Map.Entry<Serializable[], Serializable[]> row : data.getRows()) {
            try {
                SqlPair pair = sqlBuilder.forUpdate(meta,
                        row.getKey(), data.getIncludedColumnsBeforeUpdate(),
                        row.getValue(), data.getIncludedColumns(), table.columnCount);
                if (!addEntry("UPDATE", table, timestamp, pair)) {
                    return ProcessResult.STOP_LIMIT;
                }
            } catch (SqlBuildException e) {
                job.warnOnce("表 " + table.key() + " 的 UPDATE 记录已跳过: " + e.getMessage());
            }
        }
        return ProcessResult.CONTINUE;
    }

    private boolean addEntry(String type, TableInfo table, long timestamp, SqlPair pair) {
        if (keyword != null
                && !pair.redo.toLowerCase(Locale.ROOT).contains(keyword)
                && !pair.undo.toLowerCase(Locale.ROOT).contains(keyword)) {
            return true;
        }
        SqlEntry entry = new SqlEntry();
        entry.timestamp = timestamp;
        entry.type = type;
        entry.schema = table.schema;
        entry.table = table.table;
        entry.redo = pair.redo;
        entry.undo = pair.undo;
        if (!job.addEntry(entry)) {
            job.markTruncated();
            return false;
        }
        if (job.isFull()) {
            job.markTruncated();
            return false;
        }
        return true;
    }

    private boolean matchesSchema(TableInfo table) {
        if (request.schema == null || !request.schema.equals(table.schema)) {
            return false;
        }
        return request.table == null || request.table.trim().isEmpty() || request.table.equals(table.table);
    }

    private long timestamp(EventHeader header) {
        return header == null ? 0L : header.getTimestamp();
    }

    private static final class TableInfo {
        private final String schema;
        private final String table;
        private final int columnCount;

        private TableInfo(String schema, String table, int columnCount) {
            this.schema = schema;
            this.table = table;
            this.columnCount = columnCount;
        }

        private String key() {
            return schema + "." + table;
        }
    }
}
