package com.binlog.analyzer.service;

import com.binlog.analyzer.model.TableMeta;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class TableMetaResolver implements AutoCloseable {

    private final Connection connection;
    private final DictionaryService dictionaryService;
    private final Consumer<String> warningSink;
    private final Map<String, TableMeta> cache = new HashMap<>();
    private final Set<String> warnings = new HashSet<>();
    private boolean closed;

    public TableMetaResolver(Connection connection, DictionaryService dictionaryService, Consumer<String> warningSink) {
        this.connection = connection;
        this.dictionaryService = dictionaryService;
        this.warningSink = warningSink;
    }

    public TableMeta resolve(String schema, String table, int binlogColumnCount) {
        String key = schema + "." + table;
        TableMeta meta;
        if (cache.containsKey(key)) {
            meta = cache.get(key);
        } else {
            try {
                meta = dictionaryService.loadTable(connection, schema, table);
            } catch (SQLException e) {
                warnOnce("读取表 " + key + " 的数据字典失败，已跳过该表: "
                        + DictionaryService.friendlySqlMessage(e));
                meta = null;
            }
            if (meta == null) {
                warnOnce("数据库中不存在表 " + key + " 或当前账号无权访问，已跳过该表；"
                        + "如需分析已删除的表，请先恢复表结构并重新获取数据字典");
            }
            cache.put(key, meta);
        }
        if (meta == null) {
            return null;
        }
        if (binlogColumnCount > meta.columns.size()) {
            warnOnce("表 " + key + " 当前只有 " + meta.columns.size() + " 列，但 binlog 记录了 "
                    + binlogColumnCount + " 列（表结构可能已变更），为保证 SQL 安全已跳过该表");
            return null;
        }
        if (binlogColumnCount < meta.columns.size()) {
            warnOnce("表 " + key + " 当前有 " + meta.columns.size() + " 列，binlog 仅记录 "
                    + binlogColumnCount + " 列（表结构可能已变更），生成的 SQL 可能需要人工核对");
        }
        return meta;
    }

    private void warnOnce(String message) {
        if (warnings.add(message)) {
            warningSink.accept(message);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // closing best effort
        }
    }
}
