package com.binlog.analyzer.service;

import com.binlog.analyzer.model.ExportRequest;
import com.binlog.analyzer.model.SqlEntry;
import com.binlog.analyzer.util.Charsets;
import com.binlog.analyzer.util.TimeUtils;
import com.binlog.analyzer.web.ApiException;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ExportService {

    public ExportFile export(AnalysisJob job, ExportRequest request) {
        if (!job.isTerminal()) {
            throw new ApiException("分析尚未完成，暂时不能保存结果");
        }
        String type = request == null || request.type == null ? "redo" : request.type.trim().toLowerCase();
        if (!"redo".equals(type) && !"undo".equals(type)) {
            throw new ApiException("保存类型只能是 redo 或 undo");
        }

        List<SqlEntry> entries = job.entries();
        Set<Integer> selected = null;
        if (request != null && request.seqs != null && !request.seqs.isEmpty()) {
            selected = new HashSet<>(request.seqs);
            List<SqlEntry> filtered = new ArrayList<>();
            for (SqlEntry entry : entries) {
                if (selected.contains(entry.seq)) {
                    filtered.add(entry);
                }
            }
            entries = filtered;
            if (entries.isEmpty()) {
                throw new ApiException("所选记录不属于当前分析结果，请重新选择");
            }
        }

        entries.sort((a, b) -> "redo".equals(type)
                ? Integer.compare(a.seq, b.seq)
                : Integer.compare(b.seq, a.seq));

        Charset charset = Charsets.resolve(job.request.charset);
        boolean redo = "redo".equals(type);
        StringBuilder builder = new StringBuilder(entries.size() * 160 + 256);
        builder.append("# MySQL Binlog Analyzer - ").append(redo ? "Redo SQL" : "Undo SQL").append('\n');
        builder.append("# 数据库: ").append(job.request.schema).append('\n');
        if (job.request.table != null && !job.request.table.isEmpty()) {
            builder.append("# 表: ").append(job.request.table).append('\n');
        }
        builder.append("# 字符集: ").append(job.request.charset).append('\n');
        builder.append("# 生成时间: ").append(TimeUtils.formatDisplay(System.currentTimeMillis())).append('\n');
        builder.append("# 记录数量: ").append(entries.size()).append('\n');
        builder.append("# ------------------------------------------------------------\n\n");

        for (SqlEntry entry : entries) {
            String sql = redo ? entry.redo : entry.undo;
            if (sql == null || sql.isEmpty()) {
                continue;
            }
            builder.append('#').append(TimeUtils.formatDetail(entry.timestamp)).append('\n');
            builder.append(sql);
            if (!sql.endsWith(";")) {
                builder.append(';');
            }
            builder.append("\n\n");
        }

        String fileName = (redo ? "redo" : "undo") + "-sql-"
                + TimeUtils.formatFileTime(System.currentTimeMillis()) + ".sql";
        return new ExportFile(builder.toString().getBytes(charset), fileName);
    }

    public static final class ExportFile {
        public final byte[] content;
        public final String fileName;

        public ExportFile(byte[] content, String fileName) {
            this.content = content;
            this.fileName = fileName;
        }
    }
}
