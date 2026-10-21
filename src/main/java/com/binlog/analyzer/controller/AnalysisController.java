package com.binlog.analyzer.controller;

import com.binlog.analyzer.model.AnalyzeRequest;
import com.binlog.analyzer.model.ExportRequest;
import com.binlog.analyzer.model.JobView;
import com.binlog.analyzer.service.AnalysisJob;
import com.binlog.analyzer.service.AnalysisService;
import com.binlog.analyzer.service.ExportService;
import com.binlog.analyzer.web.ApiException;
import com.binlog.analyzer.web.SessionSupport;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.util.HashSet;
import java.util.Set;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private static final String SESSION_JOBS = "binlog.jobs";

    private final AnalysisService analysisService;
    private final ExportService exportService;

    public AnalysisController(AnalysisService analysisService, ExportService exportService) {
        this.analysisService = analysisService;
        this.exportService = exportService;
    }

    @PostMapping
    public JobView start(@RequestBody AnalyzeRequest request, HttpSession session) {
        JobView view = analysisService.start(request, SessionSupport.requireConnection(session));
        remember(session, view.id);
        return view;
    }

    @GetMapping("/{id}")
    public JobView get(@PathVariable("id") String id, HttpSession session) {
        requireOwned(session, id);
        return analysisService.get(id);
    }

    @PostMapping("/{id}/cancel")
    public JobView cancel(@PathVariable("id") String id, HttpSession session) {
        requireOwned(session, id);
        return analysisService.cancel(id);
    }

    @PostMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable("id") String id,
                                         @RequestBody(required = false) ExportRequest request,
                                         HttpSession session) {
        requireOwned(session, id);
        AnalysisJob job = analysisService.requireJob(id);
        ExportService.ExportFile file = exportService.export(job, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.content.length)
                .body(file.content);
    }

    @SuppressWarnings("unchecked")
    private void remember(HttpSession session, String id) {
        synchronized (session) {
            Object value = session.getAttribute(SESSION_JOBS);
            Set<String> ids = value instanceof Set ? (Set<String>) value : new HashSet<>();
            ids.add(id);
            session.setAttribute(SESSION_JOBS, ids);
        }
    }

    @SuppressWarnings("unchecked")
    private void requireOwned(HttpSession session, String id) {
        synchronized (session) {
            Object value = session.getAttribute(SESSION_JOBS);
            if (!(value instanceof Set) || !((Set<String>) value).contains(id)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "分析任务不存在或不属于当前会话，请重新分析");
            }
        }
    }
}
