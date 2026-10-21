package com.binlog.analyzer.service;

import com.binlog.analyzer.model.AnalyzeRequest;
import com.binlog.analyzer.model.JobStatus;
import com.binlog.analyzer.model.JobView;
import com.binlog.analyzer.model.SqlEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class AnalysisJob {

    private static final int MAX_WARNINGS = 50;

    public final String id = UUID.randomUUID().toString().replace("-", "");
    public final AnalyzeRequest request;
    public final Long startTimeMillis;
    public final Long endTimeMillis;
    public final int maxResults;
    public final long createdAt = System.currentTimeMillis();

    private final List<SqlEntry> entries = new ArrayList<>();
    private final Set<String> warningKeys = new LinkedHashSet<>();
    private final List<String> warnings = new ArrayList<>();
    private boolean warningOverflow;

    private volatile JobStatus status = JobStatus.PENDING;
    private volatile String phase = "等待开始";
    private volatile int progress = 0;
    private volatile long eventsRead;
    private volatile long currentFileBytes;
    private volatile String currentFile;
    private volatile String error;
    private volatile String stopReason;
    private volatile boolean truncated;
    private volatile boolean cancelRequested;
    private volatile Runnable canceller;
    private volatile long finishedAt;

    public AnalysisJob(AnalyzeRequest request, Long startTimeMillis, Long endTimeMillis, int maxResults) {
        this.request = request;
        this.startTimeMillis = startTimeMillis;
        this.endTimeMillis = endTimeMillis;
        this.maxResults = maxResults;
    }

    public synchronized void markRunning(String phase) {
        if (status == JobStatus.PENDING) {
            status = JobStatus.RUNNING;
        }
        this.phase = phase;
    }

    public void updatePhase(String phase) {
        this.phase = phase;
    }

    public void updateProgress(int progress) {
        this.progress = Math.max(0, Math.min(99, progress));
    }

    public void updateCurrentFile(String file, long bytes) {
        this.currentFile = file;
        this.currentFileBytes = bytes;
    }

    public void incrementEvents() {
        eventsRead++;
    }

    public void setCurrentFileBytes(long bytes) {
        this.currentFileBytes = bytes;
    }

    public synchronized boolean addEntry(SqlEntry entry) {
        if (entries.size() >= maxResults) {
            truncated = true;
            return false;
        }
        entry.seq = entries.size() + 1;
        entries.add(entry);
        return true;
    }

    public synchronized int entryCount() {
        return entries.size();
    }

    public synchronized boolean isFull() {
        return entries.size() >= maxResults;
    }

    public synchronized List<SqlEntry> entries() {
        return new ArrayList<>(entries);
    }

    public synchronized void warnOnce(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        if (!warningKeys.add(message)) {
            return;
        }
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(message);
        } else {
            warningOverflow = true;
        }
    }

    public synchronized void complete(JobStatus status, String message) {
        if (this.status == JobStatus.DONE || this.status == JobStatus.FAILED
                || this.status == JobStatus.CANCELLED) {
            return;
        }
        this.status = status;
        this.finishedAt = System.currentTimeMillis();
        if (status == JobStatus.FAILED) {
            this.error = message;
        } else {
            this.stopReason = message;
        }
        this.phase = status == JobStatus.DONE ? "分析完成"
                : status == JobStatus.CANCELLED ? "已取消" : "分析失败";
        if (status == JobStatus.DONE) {
            this.progress = 100;
        }
    }

    public boolean isTerminal() {
        return status == JobStatus.DONE || status == JobStatus.FAILED || status == JobStatus.CANCELLED;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setCanceller(Runnable canceller) {
        this.canceller = canceller;
    }

    public void requestCancel() {
        cancelRequested = true;
        Runnable hook = canceller;
        if (hook != null) {
            hook.run();
        }
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }

    public void markTruncated() {
        truncated = true;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public JobView view(boolean includeEntries) {
        JobView view = new JobView();
        view.id = id;
        view.status = status.name();
        view.phase = phase;
        view.progress = progress;
        view.eventsRead = eventsRead;
        view.currentFile = currentFile;
        view.error = error;
        view.stopReason = stopReason;
        view.truncated = truncated;
        view.schema = request.schema;
        view.table = request.table;
        view.online = request.online;
        view.charset = request.charset;
        long end = finishedAt > 0 ? finishedAt : System.currentTimeMillis();
        view.startedAt = createdAt;
        view.finishedAt = finishedAt;
        view.elapsedMs = end - createdAt;
        synchronized (this) {
            view.matchedRows = entries.size();
            view.totalEntries = entries.size();
            view.warnings = new ArrayList<>(warnings);
            if (warningOverflow) {
                view.warnings.add("更多提示已省略…");
            }
            if (includeEntries && isTerminal()) {
                view.entries = new ArrayList<>(entries);
            } else {
                view.entries = null;
            }
        }
        return view;
    }

    public List<SqlEntry> entriesForExport() {
        return Collections.unmodifiableList(entries());
    }
}
