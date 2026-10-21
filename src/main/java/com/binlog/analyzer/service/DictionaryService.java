package com.binlog.analyzer.service;

import com.binlog.analyzer.model.BinlogFileInfo;
import com.binlog.analyzer.model.ConnectionContext;
import com.binlog.analyzer.model.ConnectionRequest;
import com.binlog.analyzer.model.TableColumn;
import com.binlog.analyzer.model.TableMeta;
import com.binlog.analyzer.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class DictionaryService {

    private static final Set<String> SYSTEM_SCHEMAS = new HashSet<>(
            Arrays.asList("information_schema", "mysql", "performance_schema", "sys"));

    public ConnectionContext connect(ConnectionRequest request) {
        String host = validateHost(request.host);
        int port = request.port;
        if (port <= 0 || port > 65535) {
            throw new ApiException("端口号不合法: " + port);
        }
        if (request.user == null || request.user.trim().isEmpty()) {
            throw new ApiException("请输入数据库用户");
        }

        ConnectionContext temporary = new ConnectionContext(
                host, port, request.user.trim(), request.password == null ? "" : request.password, "", new ArrayList<>());
        try (Connection connection = open(temporary)) {
            String version;
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                try (ResultSet rs = statement.executeQuery("SELECT VERSION()")) {
                    version = rs.next() ? rs.getString(1) : "MySQL";
                }
            }
            List<String> schemas = listSchemas(connection);
            return new ConnectionContext(host, port, temporary.user, temporary.password, version, schemas);
        } catch (SQLException e) {
            throw new ApiException("连接数据库失败: " + friendlySqlMessage(e));
        }
    }

    public Connection open(ConnectionContext context) throws SQLException {
        return DriverManager.getConnection(context.jdbcUrl(), context.user, context.password);
    }

    public List<String> listSchemas(Connection connection) throws SQLException {
        List<String> schemas = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery("SHOW DATABASES")) {
                while (rs.next()) {
                    schemas.add(rs.getString(1));
                }
            }
        }
        schemas.sort(schemaComparator());
        return schemas;
    }

    public List<String> listTables(Connection connection, String schema) throws SQLException {
        if (schema == null || schema.trim().isEmpty()) {
            return new ArrayList<>();
        }
        List<String> tables = new ArrayList<>();
        String sql = "SHOW FULL TABLES FROM " + quote(schema);
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(15);
            try (ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    String type = rs.getString(2);
                    if ("BASE TABLE".equalsIgnoreCase(type)) {
                        tables.add(rs.getString(1));
                    }
                }
            }
        }
        tables.sort(String.CASE_INSENSITIVE_ORDER);
        return tables;
    }

    public List<String> listTables(ConnectionContext context, String schema) {
        try (Connection connection = open(context)) {
            return listTables(connection, schema);
        } catch (SQLException e) {
            throw new ApiException("读取数据表失败: " + friendlySqlMessage(e));
        }
    }

    public List<BinlogFileInfo> listBinlogFiles(ConnectionContext context) {
        try (Connection connection = open(context)) {
            return listBinlogFiles(connection);
        } catch (SQLException e) {
            throw new ApiException("读取 binlog 列表失败: " + friendlySqlMessage(e));
        }
    }

    public List<BinlogFileInfo> listBinlogFiles(Connection connection) throws SQLException {
        List<BinlogFileInfo> files = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(15);
            try (ResultSet rs = statement.executeQuery("SHOW BINARY LOGS")) {
                int nameIndex = findColumn(rs, "Log_name", 1);
                int sizeIndex = findColumn(rs, "File_size", 2);
                int encryptedIndex = findColumn(rs, "Encrypted", -1);
                while (rs.next()) {
                    String name = rs.getString(nameIndex);
                    long size = rs.getLong(sizeIndex);
                    boolean encrypted = encryptedIndex > 0 && "YES".equalsIgnoreCase(rs.getString(encryptedIndex));
                    files.add(new BinlogFileInfo(name, size, encrypted));
                }
            }
        }
        return files;
    }

    public TableMeta loadTable(Connection connection, String schema, String table) throws SQLException {
        if (schema == null || table == null || schema.trim().isEmpty() || table.trim().isEmpty()) {
            return null;
        }
        String sql = "SHOW FULL COLUMNS FROM " + quote(schema) + "." + quote(table);
        TableMeta meta = new TableMeta();
        meta.schema = schema;
        meta.table = table;
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(15);
            try (ResultSet rs = statement.executeQuery(sql)) {
                int ordinal = 0;
                while (rs.next()) {
                    String name = rs.getString("Field");
                    String type = rs.getString("Type");
                    String collation = rs.getString("Collation");
                    boolean nullable = "YES".equalsIgnoreCase(rs.getString("Null"));
                    boolean primary = "PRI".equalsIgnoreCase(rs.getString("Key"));
                    TableColumn column = new TableColumn(name, type, collation, nullable, primary, ordinal++);
                    meta.columns.add(column);
                    if (primary) {
                        meta.primaryKeys.add(name);
                    }
                }
            }
        }
        if (meta.columns.isEmpty()) {
            return null;
        }
        return meta;
    }

    public static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    public static String friendlySqlMessage(SQLException e) {
        int code = e.getErrorCode();
        String message = e.getMessage();
        if (code == 1045) {
            return "用户名或密码错误（Access denied）";
        }
        if (code == 1049) {
            return "数据库不存在";
        }
        if (code == 1142 || code == 1227) {
            return "当前账号权限不足：" + message;
        }
        if (code == 2003 || message == null || message.isEmpty()) {
            return "无法连接服务器，请检查主机、端口与网络（" + rootMessage(e) + "）";
        }
        return message;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private int findColumn(ResultSet rs, String label, int fallback) {
        try {
            return rs.findColumn(label);
        } catch (SQLException e) {
            return fallback;
        }
    }

    private String validateHost(String host) {
        if (host == null || host.trim().isEmpty()) {
            throw new ApiException("请输入主机名或 IP 地址");
        }
        String value = host.trim();
        if (value.length() > 255 || !value.matches("[A-Za-z0-9._:\\-\\[\\]]+")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "主机名或 IP 地址格式不正确");
        }
        return value;
    }

    private Comparator<String> schemaComparator() {
        return (a, b) -> {
            boolean systemA = SYSTEM_SCHEMAS.contains(a.toLowerCase());
            boolean systemB = SYSTEM_SCHEMAS.contains(b.toLowerCase());
            if (systemA != systemB) {
                return systemA ? 1 : -1;
            }
            return String.CASE_INSENSITIVE_ORDER.compare(a, b);
        };
    }
}
