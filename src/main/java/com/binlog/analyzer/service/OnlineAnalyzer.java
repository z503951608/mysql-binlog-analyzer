package com.binlog.analyzer.service;

import com.binlog.analyzer.config.BinlogProperties;
import com.binlog.analyzer.model.BinlogFileInfo;
import com.binlog.analyzer.model.ConnectionContext;
import com.binlog.analyzer.model.JobStatus;
import com.binlog.analyzer.web.ApiException;
import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.RotateEventData;
import com.github.shyiko.mysql.binlog.network.SSLMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class OnlineAnalyzer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(OnlineAnalyzer.class);

    private final AnalysisJob job;
    private final ConnectionContext connectionContext;
    private final DictionaryService dictionaryService;
    private final BinlogProperties properties;

    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final Map<String, Integer> fileIndexes = new HashMap<>();
    private final Map<String, Long> fileSizes = new HashMap<>();
    private final java.util.List<String> fileNames = new java.util.ArrayList<>();

    private volatile BinaryLogClient client;
    private volatile boolean connected;
    private volatile boolean atLastFile;
    private volatile String currentFile;
    private volatile long currentFileBaseBytes;
    private volatile long totalBytesInRange;
    private volatile long lastActivity = System.currentTimeMillis();
    private volatile long lastProgressAt;
    private volatile int lastFileIndex;
    private volatile int startFileIndex;
    private volatile String startFile;
    private volatile Throwable engineFailure;
    private volatile Exception connectionFailure;
    private volatile ScheduledExecutorService watchdogExecutor;
    private volatile ExecutorService disconnectExecutor;

    public OnlineAnalyzer(AnalysisJob job, ConnectionContext connectionContext,
                          DictionaryService dictionaryService, BinlogProperties properties) {
        this.job = job;
        this.connectionContext = connectionContext;
        this.dictionaryService = dictionaryService;
        this.properties = properties;
    }

    @Override
    public void run() {
        if (job.isCancelRequested()) {
            job.complete(JobStatus.CANCELLED, "用户取消分析");
            return;
        }

        Connection connection = null;
        TableMetaResolver resolver = null;
        try {
            job.markRunning("获取在线 binlog 列表");
            job.updateProgress(2);
            connection = dictionaryService.open(connectionContext);

            List<BinlogFileInfo> files;
            try {
                files = dictionaryService.listBinlogFiles(connection);
            } catch (SQLException e) {
                throw new ApiException("获取在线 binlog 列表失败（需要 REPLICATION CLIENT / REPLICATION SLAVE 权限）: "
                        + DictionaryService.friendlySqlMessage(e));
            }
            if (files.isEmpty()) {
                throw new ApiException("服务器上没有可用的 binlog，请确认已开启 log_bin");
            }

            for (int i = 0; i < files.size(); i++) {
                BinlogFileInfo file = files.get(i);
                fileIndexes.put(file.name, i);
                fileSizes.put(file.name, file.size);
                fileNames.add(file.name);
            }
            lastFileIndex = files.size() - 1;

            startFile = job.request.startFile;
            if (startFile == null || startFile.trim().isEmpty()) {
                startFile = files.get(lastFileIndex).name;
            } else {
                startFile = startFile.trim();
            }
            Integer index = fileIndexes.get(startFile);
            if (index == null) {
                throw new ApiException("开始日志 " + startFile + " 不在服务器当前 binlog 列表中，请刷新日志列表后重试");
            }
            startFileIndex = index;
            currentFile = startFile;
            atLastFile = startFileIndex == lastFileIndex;
            job.updateCurrentFile(startFile, 0);

            int rangeEnd = job.request.analyzeToLatest ? lastFileIndex : startFileIndex;
            long total = 0;
            for (int i = startFileIndex; i <= rangeEnd; i++) {
                total += files.get(i).size;
            }
            totalBytesInRange = Math.max(1, total);

            resolver = new TableMetaResolver(connection, dictionaryService, job::warnOnce);
            connection = null;

            BinlogEventEngine engine = new BinlogEventEngine(job, resolver);

            disconnectExecutor = Executors.newSingleThreadExecutor(daemonFactory("binlog-disconnect"));
            startWatchdog();

            BinaryLogClient binlogClient = new BinaryLogClient(
                    connectionContext.host, connectionContext.port,
                    connectionContext.user, connectionContext.password);
            client = binlogClient;
            long serverId = properties.getClientServerId() + Math.abs(job.id.hashCode() % 1000);
            binlogClient.setServerId(serverId <= 0 ? 20200621 : serverId);
            binlogClient.setBinlogFilename(startFile);
            binlogClient.setBinlogPosition(4);
            binlogClient.setKeepAlive(false);
            binlogClient.setHeartbeatInterval(0);
            binlogClient.setConnectTimeout(15000);
            binlogClient.setSSLMode(SSLMode.PREFERRED);
            binlogClient.setEventDeserializer(BinlogDeserializerFactory.create());
            binlogClient.setTransparentlyDecompressTransactions(true);
            binlogClient.registerLifecycleListener(new BinaryLogClient.AbstractLifecycleListener() {
                @Override
                public void onConnect(BinaryLogClient c) {
                    connected = true;
                    lastActivity = System.currentTimeMillis();
                    job.updatePhase("正在分析在线 binlog");
                }

                @Override
                public void onDisconnect(BinaryLogClient c) {
                    connected = false;
                }
            });
            binlogClient.registerEventListener(event -> handleEvent(engine, event));
            job.setCanceller(() -> requestStop("用户取消分析"));

            if (job.isCancelRequested()) {
                job.complete(JobStatus.CANCELLED, "用户取消分析");
                return;
            }

            job.updatePhase("正在连接 MySQL binlog");
            job.updateProgress(5);
            try {
                binlogClient.connect();
            } catch (Exception e) {
                if (!stopping.get() && !job.isCancelRequested()) {
                    connectionFailure = e;
                }
            }

            if (job.isCancelRequested()) {
                job.complete(JobStatus.CANCELLED, "用户取消分析");
            } else if (engineFailure != null) {
                job.complete(JobStatus.FAILED, "分析过程中发生错误: " + message(engineFailure));
            } else if (connectionFailure != null) {
                job.complete(JobStatus.FAILED, "连接 binlog 失败: " + message(connectionFailure));
            } else if (stopping.get()) {
                job.complete(JobStatus.DONE, job.getStopReason() == null ? "在线分析已结束" : job.getStopReason());
            } else {
                job.complete(JobStatus.FAILED, "binlog 连接已意外断开，分析结果可能不完整");
            }
        } catch (ApiException e) {
            job.complete(JobStatus.FAILED, e.getMessage());
        } catch (SQLException e) {
            job.complete(JobStatus.FAILED, "连接数据库失败: " + DictionaryService.friendlySqlMessage(e));
        } catch (Exception e) {
            log.error("Online analysis failed, job={}", job.id, e);
            job.complete(JobStatus.FAILED, "在线分析失败: " + message(e));
        } finally {
            stopWatchdog();
            BinaryLogClient finalClient = client;
            if (finalClient != null && finalClient.isConnected()) {
                try {
                    finalClient.disconnect();
                } catch (IOException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("Close binlog client failed", e);
                    }
                }
            }
            if (job.getStatus() == JobStatus.PENDING || job.getStatus() == JobStatus.RUNNING) {
                job.complete(JobStatus.FAILED, "在线分析异常终止");
            }
            if (resolver != null) {
                resolver.close();
            } else if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // best effort
                }
            }
        }
    }

    private void startWatchdog() {
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(daemonFactory("binlog-watchdog"));
        watchdogExecutor = watchdog;
        watchdog.scheduleWithFixedDelay(() -> {
            try {
                if (stopping.get()) {
                    return;
                }
                if (job.isCancelRequested()) {
                    requestStop("用户取消分析");
                    return;
                }
                if (!connected) {
                    return;
                }
                long idle = System.currentTimeMillis() - lastActivity;
                if (atLastFile && idle > properties.getIdleTimeoutMs()) {
                    requestStop("已到达最新日志末尾（空闲 " + Math.max(1, idle / 1000) + " 秒后结束）");
                }
            } catch (Throwable t) {
                engineFailure = t;
                requestStop("监控分析任务时发生错误: " + message(t));
            }
        }, 500, 500, TimeUnit.MILLISECONDS);
    }

    private void handleEvent(BinlogEventEngine engine, Event event) {
        if (stopping.get()) {
            return;
        }
        lastActivity = System.currentTimeMillis();
        try {
            if (event != null && event.getData() instanceof RotateEventData) {
                RotateEventData rotate = (RotateEventData) event.getData();
                if (!handleRotate(rotate.getBinlogFilename())) {
                    return;
                }
            }
            updateProgress(event);
            BinlogEventEngine.ProcessResult result = engine.process(event);
            if (result == BinlogEventEngine.ProcessResult.STOP_END_TIME) {
                requestStop("已到达结束时间");
            } else if (result == BinlogEventEngine.ProcessResult.STOP_LIMIT) {
                requestStop("已达到最多 " + job.maxResults + " 条记录的上限");
            }
        } catch (Throwable t) {
            engineFailure = t;
            requestStop("分析事件时发生错误: " + message(t));
        }
    }

    private boolean handleRotate(String nextFile) {
        if (nextFile == null || nextFile.isEmpty()) {
            return true;
        }
        if (!job.request.analyzeToLatest) {
            if (!nextFile.equals(startFile)) {
                currentFile = nextFile;
                requestStop("已分析完所选日志");
                return false;
            }
            atLastFile = startFileIndex == lastFileIndex;
            return true;
        }

        Integer index = fileIndexes.get(nextFile);
        if (index == null || index > lastFileIndex) {
            currentFile = nextFile;
            requestStop("已到达最新日志末尾");
            return false;
        }
        currentFile = nextFile;
        atLastFile = index == lastFileIndex;
        currentFileBaseBytes = bytesBefore(index);
        job.updateCurrentFile(nextFile, 0);
        if (atLastFile) {
            lastActivity = System.currentTimeMillis();
        }
        return true;
    }

    private void updateProgress(Event event) {
        long now = System.currentTimeMillis();
        if (now - lastProgressAt < 250) {
            return;
        }
        lastProgressAt = now;
        long position = 4;
        if (event != null && event.getHeader() instanceof EventHeaderV4) {
            position = ((EventHeaderV4) event.getHeader()).getNextPosition();
        }
        long fileSize = fileSizes.containsKey(currentFile) ? fileSizes.get(currentFile) : 0L;
        long processed = currentFileBaseBytes + Math.min(Math.max(position, 0L), fileSize);
        int progress = 5 + (int) (88 * Math.min(processed, totalBytesInRange) / totalBytesInRange);
        job.updateProgress(progress);
        job.setCurrentFileBytes(position);
    }

    private long bytesBefore(int index) {
        if (index <= startFileIndex) {
            return 0;
        }
        long total = 0;
        for (int i = startFileIndex; i < index && i < fileNames.size(); i++) {
            total += fileSizes.getOrDefault(fileNames.get(i), 0L);
        }
        return total;
    }

    private void requestStop(String reason) {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        job.setStopReason(reason);
        job.updatePhase("正在结束分析…");
        BinaryLogClient currentClient = client;
        ExecutorService executor = disconnectExecutor;
        if (currentClient != null) {
            Runnable disconnect = () -> {
                try {
                    currentClient.disconnect();
                } catch (IOException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("Disconnect binlog client failed", e);
                    }
                }
            };
            if (executor != null) {
                executor.execute(disconnect);
            } else {
                Thread thread = new Thread(disconnect, "binlog-stop");
                thread.setDaemon(true);
                thread.start();
            }
        }
    }

    private void stopWatchdog() {
        ScheduledExecutorService watchdog = watchdogExecutor;
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
        ExecutorService executor = disconnectExecutor;
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private ThreadFactory daemonFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + job.id.substring(0, 6));
            thread.setDaemon(true);
            return thread;
        };
    }

    private String message(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return current.getClass().getSimpleName();
        }
        if (message.contains("Access denied")) {
            return "访问被拒绝，请检查数据库账号、密码及 REPLICATION SLAVE 权限（" + message + "）";
        }
        return message;
    }
}
