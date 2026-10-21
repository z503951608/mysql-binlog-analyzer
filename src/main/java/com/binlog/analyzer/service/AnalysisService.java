package com.binlog.analyzer.service;

import com.binlog.analyzer.config.BinlogProperties;
import com.binlog.analyzer.model.AnalyzeRequest;
import com.binlog.analyzer.model.ConnectionContext;
import com.binlog.analyzer.model.JobStatus;
import com.binlog.analyzer.model.JobView;
import com.binlog.analyzer.util.Charsets;
import com.binlog.analyzer.util.TimeUtils;
import com.binlog.analyzer.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final DictionaryService dictionaryService;
    private final UploadService uploadService;
    private final BinlogProperties properties;
    private final Map<String, AnalysisJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public AnalysisService(DictionaryService dictionaryService, UploadService uploadService, BinlogProperties properties) {
        this.dictionaryService = dictionaryService;
        this.uploadService = uploadService;
        this.properties = properties;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "binlog-analysis-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(4, factory);
    }

    public JobView start(AnalyzeRequest request, ConnectionContext connectionContext) {
        if (request == null) {
            throw new ApiException("分析参数不能为空");
        }
        validateAndNormalize(request);

        Long startMillis = TimeUtils.parseDateTime(request.startTime);
        Long endMillis = TimeUtils.parseDateTime(request.endTime);
        if (startMillis != null && endMillis != null && startMillis > endMillis) {
            throw new ApiException("开始时间不能晚于结束时间");
        }

        AnalysisJob job = new AnalysisJob(request, startMillis, endMillis, Math.max(1, properties.getMaxResults()));
        UploadService.ResolvedUpload upload = null;
        if (!request.online) {
            upload = uploadService.resolve(request.uploadId);
            job.updateCurrentFile(upload.info.originalName, 0);
        }

        jobs.put(job.id, job);
        final UploadService.ResolvedUpload resolvedUpload = upload;
        Runnable analyzer = request.online
                ? new OnlineAnalyzer(job, connectionContext, dictionaryService, properties)
                : new OfflineAnalyzer(job, connectionContext, dictionaryService,
                        resolvedUpload.file, resolvedUpload.info.originalName);
        try {
            executor.submit(() -> {
                try {
                    analyzer.run();
                } catch (Throwable t) {
                    log.error("Analysis task crashed, job={}", job.id, t);
                    job.complete(JobStatus.FAILED, "分析任务异常终止: " + t.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            jobs.remove(job.id);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "分析线程池已关闭，请稍后重试");
        }
        log.info("Analysis job started: id={}, online={}, schema={}, table={}",
                job.id, request.online, request.schema, request.table);
        return job.view(false);
    }

    public JobView get(String id) {
        return requireJob(id).view(true);
    }

    public JobView cancel(String id) {
        AnalysisJob job = requireJob(id);
        if (!job.isTerminal()) {
            job.requestCancel();
        }
        return job.view(false);
    }

    public AnalysisJob requireJob(String id) {
        AnalysisJob job = jobs.get(id);
        if (job == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "分析任务不存在或已过期，请重新分析");
        }
        return job;
    }

    private void validateAndNormalize(AnalyzeRequest request) {
        if (request.schema == null || request.schema.trim().isEmpty()) {
            throw new ApiException("请选择要分析的数据库");
        }
        request.schema = request.schema.trim();
        request.table = request.table == null ? "" : request.table.trim();
        if (request.schema.length() > 128 || request.table.length() > 128) {
            throw new ApiException("数据库或表名过长");
        }

        String operation = request.operation == null ? "all" : request.operation.trim().toLowerCase(Locale.ROOT);
        if (!operation.matches("all|insert|update|delete")) {
            throw new ApiException("操作类型只能是 all、insert、update、delete");
        }
        request.operation = operation;

        String display = request.display == null ? "all" : request.display.trim().toLowerCase(Locale.ROOT);
        if (!display.matches("all|redo|undo")) {
            throw new ApiException("显示类型只能是 all、redo、undo");
        }
        request.display = display;

        request.charset = Charsets.canonicalName(request.charset);
        request.keyword = request.keyword == null ? "" : request.keyword.trim();
        if (request.keyword.length() > 500) {
            throw new ApiException("关键字长度不能超过 500 个字符");
        }
        if (request.online) {
            request.uploadId = null;
            if (request.startFile != null) {
                request.startFile = request.startFile.trim();
                if (request.startFile.length() > 255) {
                    throw new ApiException("binlog 文件名过长");
                }
            }
        } else {
            request.analyzeToLatest = false;
            if (request.uploadId == null || request.uploadId.trim().isEmpty()) {
                throw new ApiException("请先上传离线 binlog 文件");
            }
        }
    }

    @Scheduled(fixedDelay = 300000L)
    public void cleanupJobs() {
        long expireBefore = System.currentTimeMillis() - properties.getJobRetentionMinutes() * 60_000L;
        List<AnalysisJob> removable = new ArrayList<>();
        for (AnalysisJob job : jobs.values()) {
            if (job.isTerminal()) {
                JobView view = job.view(false);
                if (view.finishedAt > 0 && view.finishedAt < expireBefore) {
                    removable.add(job);
                }
            }
        }
        removable.forEach(job -> jobs.remove(job.id));

        if (jobs.size() > 100) {
            jobs.values().stream()
                    .filter(AnalysisJob::isTerminal)
                    .sorted(Comparator.comparingLong(job -> job.view(false).finishedAt))
                    .limit(jobs.size() - 100L)
                    .forEach(job -> jobs.remove(job.id));
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
