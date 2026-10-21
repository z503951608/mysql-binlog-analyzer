package com.binlog.analyzer.service;

import com.binlog.analyzer.model.ConnectionContext;
import com.binlog.analyzer.model.JobStatus;
import com.binlog.analyzer.util.CountingInputStream;
import com.github.shyiko.mysql.binlog.BinaryLogFileReader;
import com.github.shyiko.mysql.binlog.event.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

public class OfflineAnalyzer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(OfflineAnalyzer.class);

    private final AnalysisJob job;
    private final ConnectionContext connectionContext;
    private final DictionaryService dictionaryService;
    private final File binlogFile;
    private final String displayName;

    public OfflineAnalyzer(AnalysisJob job, ConnectionContext connectionContext,
                           DictionaryService dictionaryService, File binlogFile, String displayName) {
        this.job = job;
        this.connectionContext = connectionContext;
        this.dictionaryService = dictionaryService;
        this.binlogFile = binlogFile;
        this.displayName = displayName;
    }

    @Override
    public void run() {
        if (job.isCancelRequested()) {
            job.complete(JobStatus.CANCELLED, "用户取消分析");
            return;
        }
        job.markRunning("连接数据库数据字典");
        job.updateCurrentFile(displayName, 0);
        long totalBytes = binlogFile.length();
        job.updateProgress(1);

        Connection connection = null;
        TableMetaResolver resolver = null;
        try {
            connection = dictionaryService.open(connectionContext);
            resolver = new TableMetaResolver(connection, dictionaryService, job::warnOnce);

            try (CountingInputStream counting = new CountingInputStream(new FileInputStream(binlogFile));
                 BinaryLogFileReader reader = new BinaryLogFileReader(counting, BinlogDeserializerFactory.create())) {

                BinlogEventEngine engine = new BinlogEventEngine(job, resolver);
                job.updatePhase("正在分析离线 binlog");
                job.updateProgress(3);

                long lastUpdate = 0;
                while (true) {
                    if (job.isCancelRequested()) {
                        job.complete(JobStatus.CANCELLED, "用户取消分析");
                        return;
                    }
                    Event event = reader.readEvent();
                    if (event == null) {
                        break;
                    }
                    BinlogEventEngine.ProcessResult result = engine.process(event);
                    if (result == BinlogEventEngine.ProcessResult.STOP_LIMIT) {
                        job.setStopReason("已达到最多 " + job.maxResults + " 条记录的上限");
                        break;
                    }
                    if (result == BinlogEventEngine.ProcessResult.STOP_END_TIME) {
                        job.setStopReason("已到达结束时间");
                        break;
                    }

                    long now = System.currentTimeMillis();
                    long count = counting.getCount();
                    if (now - lastUpdate > 250 || count >= totalBytes) {
                        lastUpdate = now;
                        int progress = totalBytes > 0 ? 3 + (int) (count * 94 / totalBytes) : 50;
                        job.updateProgress(progress);
                        job.updateCurrentFile(displayName, count);
                    }
                }

                if (job.isCancelRequested()) {
                    job.complete(JobStatus.CANCELLED, "用户取消分析");
                } else {
                    String reason = job.getStopReason();
                    job.complete(JobStatus.DONE, reason == null ? "已分析完离线 binlog 文件" : reason);
                }
            }
        } catch (SQLException e) {
            job.complete(JobStatus.FAILED, "连接数据库失败: " + DictionaryService.friendlySqlMessage(e));
        } catch (IOException e) {
            job.complete(JobStatus.FAILED, "读取 binlog 文件失败: " + message(e));
        } catch (Exception e) {
            log.error("Offline analysis failed, job={}", job.id, e);
            job.complete(JobStatus.FAILED, "离线分析失败: " + message(e));
        } finally {
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

    private String message(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
