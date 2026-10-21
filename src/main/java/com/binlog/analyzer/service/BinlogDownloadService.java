package com.binlog.analyzer.service;

import com.binlog.analyzer.config.BinlogProperties;
import com.binlog.analyzer.model.BinlogFileInfo;
import com.binlog.analyzer.model.ConnectionContext;
import com.binlog.analyzer.model.DownloadTicket;
import com.binlog.analyzer.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class BinlogDownloadService {

    private static final Logger log = LoggerFactory.getLogger(BinlogDownloadService.class);

    private static final String SESSION_DOWNLOAD_KEY = "binlog.downloads";
    private static final Pattern BINLOG_FILE_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,255}");
    private static final long STALE_MILLIS = 60 * 60 * 1000L;
    private static final String BUNDLED_DIRECTORY = "tools/mysqlbinlog/";
    private static final String[] BUNDLED_FILES = {
            "mysqlbinlog.exe",
            "libcrypto-1_1-x64.dll",
            "libssl-1_1-x64.dll",
            "msvcp140.dll",
            "vcruntime140.dll",
            "vcruntime140_1.dll"
    };

    private final DictionaryService dictionaryService;
    private final BinlogProperties properties;
    private final Path downloadDirectory;

    private volatile File cachedExecutable;

    public BinlogDownloadService(DictionaryService dictionaryService, BinlogProperties properties) {
        this.dictionaryService = dictionaryService;
        this.properties = properties;
        this.downloadDirectory = new File(System.getProperty("java.io.tmpdir"), "binlog-downloads")
                .toPath().toAbsolutePath().normalize();
    }

    public DownloadTicket prepare(ConnectionContext context, String requestedFile, HttpSession session) {
        String fileName = normalizeFileName(requestedFile);
        long size = findRemoteFile(context, fileName);
        File executable = resolveExecutable();

        Path workDirectory = null;
        try {
            workDirectory = Files.createTempDirectory("binlog-");
            Files.createDirectories(downloadDirectory);
            File target = new File(workDirectory.toFile(), fileName);

            List<String> command = new ArrayList<>();
            command.add(executable.getAbsolutePath());
            command.add("--no-defaults");
            command.add("--read-from-remote-server");
            command.add("--host=" + context.host);
            command.add("--port=" + context.port);
            command.add("--user=" + context.user);
            command.add("--raw");
            command.add("--stop-position=" + size);
            command.add(fileName);

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workDirectory.toFile());
            builder.environment().put("MYSQL_PWD", context.password == null ? "" : context.password);
            File stdout = new File(workDirectory.toFile(), "stdout.log");
            File stderr = new File(workDirectory.toFile(), "stderr.log");
            builder.redirectOutput(stdout);
            builder.redirectError(stderr);

            Path stored = downloadDirectory.resolve(UUID.randomUUID().toString().replace("-", "") + ".binlog");
            Process process;
            try {
                process = builder.start();
            } catch (IOException e) {
                throw new ApiException("无法启动 mysqlbinlog：" + e.getMessage()
                        + "。请在 application.yml 中配置 binlog.mysqlbinlog-path");
            }
            boolean finished;
            try {
                finished = process.waitFor(Math.max(10, properties.getDownloadTimeoutSeconds()), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new ApiException("下载 binlog 被中断，请重试");
            }
            if (!finished) {
                process.destroyForcibly();
                throw new ApiException("下载 binlog 超时（超过 " + properties.getDownloadTimeoutSeconds()
                        + " 秒），可稍后重试或直接复制服务器上的日志文件进行离线分析");
            }

            String errorText = readTail(stderr, 8192);
            if (process.exitValue() != 0) {
                throw new ApiException(friendlyError(errorText, process.exitValue()));
            }
            if (!target.isFile() || target.length() == 0) {
                throw new ApiException("mysqlbinlog 未生成 binlog 文件" + suffix(errorText));
            }
            Files.move(target.toPath(), stored, StandardCopyOption.REPLACE_EXISTING);

            String token = register(session, stored, fileName, Files.size(stored));
            DownloadTicket ticket = new DownloadTicket();
            ticket.url = "/api/binlogs/stream?token=" + token;
            ticket.fileName = fileName;
            ticket.size = Files.size(stored);
            return ticket;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "下载 binlog 失败: " + e.getMessage());
        } finally {
            deleteDirectoryQuietly(workDirectory);
        }
    }

    public Artifact take(HttpSession session, String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "下载链接无效");
        }
        Map<String, Artifact> downloads = downloads(session);
        Artifact artifact = downloads.remove(token.trim());
        if (artifact == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "下载链接已失效，请重新下载");
        }
        if (!Files.isRegularFile(artifact.path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "下载文件已被清理，请重新下载");
        }
        return artifact;
    }

    private long findRemoteFile(ConnectionContext context, String fileName) {
        try (Connection connection = dictionaryService.open(context)) {
            List<BinlogFileInfo> files = dictionaryService.listBinlogFiles(connection);
            for (BinlogFileInfo info : files) {
                if (info.name.equalsIgnoreCase(fileName)) {
                    if (info.encrypted) {
                        throw new ApiException("binlog 文件 " + fileName
                                + " 已加密，mysqlbinlog 无法在无密钥环的情况下读取，请在服务器上关闭 binlog 加密后重试");
                    }
                    return info.size;
                }
            }
        } catch (java.sql.SQLException e) {
            throw new ApiException("读取 binlog 列表失败: " + DictionaryService.friendlySqlMessage(e));
        }
        throw new ApiException("服务器上不存在 binlog 文件 " + fileName);
    }

    private String register(HttpSession session, Path path, String fileName, long size) {
        Map<String, Artifact> downloads = downloads(session);
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Artifact> entry : downloads.entrySet()) {
            if (now - entry.getValue().createdAt > STALE_MILLIS) {
                downloads.remove(entry.getKey());
            }
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        downloads.put(token, new Artifact(path, fileName, size, now));
        return token;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Artifact> downloads(HttpSession session) {
        synchronized (session) {
            Object value = session.getAttribute(SESSION_DOWNLOAD_KEY);
            if (value instanceof Map) {
                return (Map<String, Artifact>) value;
            }
            Map<String, Artifact> created = new ConcurrentHashMap<>();
            session.setAttribute(SESSION_DOWNLOAD_KEY, created);
            return created;
        }
    }

    private String normalizeFileName(String requestedFile) {
        if (requestedFile == null || requestedFile.trim().isEmpty()) {
            throw new ApiException("请选择要下载的 binlog 文件");
        }
        String value = requestedFile.trim().replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        if (!BINLOG_FILE_PATTERN.matcher(value).matches() || value.startsWith(".")) {
            throw new ApiException("binlog 文件名不合法");
        }
        return value;
    }

    private File resolveExecutable() {
        File cached = cachedExecutable;
        if (cached != null && cached.isFile()) {
            return cached;
        }
        String configured = properties.getMysqlbinlogPath();
        if (configured != null && !configured.trim().isEmpty()) {
            File file = normalizeExecutable(configured.trim());
            if (file == null || !file.isFile()) {
                throw new ApiException("配置的 mysqlbinlog 路径不存在：" + configured
                        + "，请检查 application.yml 中的 binlog.mysqlbinlog-path");
            }
            cachedExecutable = file;
            return file;
        }
        String fromEnv = System.getenv("MYSQLBINLOG_PATH");
        if (fromEnv != null && !fromEnv.trim().isEmpty()) {
            File file = normalizeExecutable(fromEnv.trim());
            if (file != null && file.isFile()) {
                cachedExecutable = file;
                return file;
            }
        }
        File bundled = extractBundledExecutable();
        if (bundled != null) {
            cachedExecutable = bundled;
            return bundled;
        }
        File fromPath = searchPath();
        if (fromPath != null) {
            cachedExecutable = fromPath;
            return fromPath;
        }
        throw new ApiException("未找到 mysqlbinlog，请检查 application.yml 中的 binlog.mysqlbinlog-path，或将 mysqlbinlog 加入系统 PATH");
    }

    private File extractBundledExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String entryName = windows ? "mysqlbinlog.exe" : "mysqlbinlog";
        ClassLoader classLoader = getClass().getClassLoader();
        if (classLoader.getResource(BUNDLED_DIRECTORY + entryName) == null) {
            return null;
        }
        try {
            Path directory = Files.createTempDirectory("binlog-mysqlbinlog-");
            directory.toFile().deleteOnExit();
            for (String fileName : BUNDLED_FILES) {
                if (!windows && !fileName.equals(entryName)) {
                    continue;
                }
                try (InputStream input = classLoader.getResourceAsStream(BUNDLED_DIRECTORY + fileName)) {
                    if (input == null) {
                        continue;
                    }
                    Path target = directory.resolve(fileName);
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                    target.toFile().deleteOnExit();
                }
            }
            Path executable = directory.resolve(entryName);
            if (!Files.isRegularFile(executable)) {
                return null;
            }
            if (!windows) {
                Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rwx------");
                Files.setPosixFilePermissions(executable, permissions);
            }
            log.info("Using bundled mysqlbinlog extracted to {}", executable);
            return executable.toFile();
        } catch (IOException e) {
            log.warn("Failed to extract bundled mysqlbinlog", e);
            return null;
        }
    }

    private File searchPath() {
        String path = System.getenv("PATH");
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "mysqlbinlog.exe" : "mysqlbinlog";
        for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
            if (entry == null || entry.trim().isEmpty()) {
                continue;
            }
            File file = new File(entry.trim(), executable);
            if (file.isFile()) {
                return file;
            }
        }
        return null;
    }

    private File normalizeExecutable(String value) {
        File file = new File(value);
        if (file.isDirectory()) {
            String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                    ? "mysqlbinlog.exe" : "mysqlbinlog";
            return new File(file, executable);
        }
        return file;
    }

    private String friendlyError(String errorText, int exitCode) {
        String text = errorText == null ? "" : errorText.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        String message;
        if (lower.contains("access denied")) {
            message = "MySQL 拒绝访问（Access denied），请确认账号具有 REPLICATION SLAVE 权限";
        } else if (lower.contains("unknown variable") && lower.contains("ssl-mode")) {
            message = "当前 mysqlbinlog 版本过旧，请升级 mysqlbinlog 或改用离线分析";
        } else if (lower.contains("could not find first log file") || lower.contains("error 1236")) {
            message = "服务器无法提供该 binlog（日志可能已被清理）";
        } else if (lower.contains("can't connect") || lower.contains("unknown mysql server host")) {
            message = "mysqlbinlog 无法连接 MySQL 服务器，请检查主机、端口和防火墙";
        } else {
            message = "mysqlbinlog 执行失败（退出码 " + exitCode + "）" + suffix(text);
        }
        return message;
    }

    private String suffix(String text) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.length() > 800) {
            value = value.substring(value.length() - 800);
        }
        return "：" + value;
    }

    private String readTail(File file, int limit) {
        if (file == null || !file.isFile()) {
            return "";
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            int start = Math.max(0, bytes.length - limit);
            return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private void deleteDirectoryQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    @Scheduled(fixedDelay = 600000L)
    public void cleanupStaleDownloads() {
        if (!Files.isDirectory(downloadDirectory)) {
            return;
        }
        long expireBefore = System.currentTimeMillis() - STALE_MILLIS;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(downloadDirectory, "*.binlog")) {
            for (Path path : stream) {
                try {
                    if (Files.getLastModifiedTime(path).toMillis() < expireBefore) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException ignored) {
                    // best effort
                }
            }
        } catch (IOException e) {
            log.debug("Cleanup download directory failed: {}", e.getMessage());
        }
    }

    public static final class Artifact {
        public final Path path;
        public final String fileName;
        public final long size;
        public final long createdAt;

        public Artifact(Path path, String fileName, long size, long createdAt) {
            this.path = path;
            this.fileName = fileName;
            this.size = size;
            this.createdAt = createdAt;
        }
    }
}
