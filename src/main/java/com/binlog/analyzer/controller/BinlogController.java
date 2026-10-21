package com.binlog.analyzer.controller;

import com.binlog.analyzer.model.BinlogFileInfo;
import com.binlog.analyzer.model.DownloadRequest;
import com.binlog.analyzer.model.DownloadTicket;
import com.binlog.analyzer.service.BinlogDownloadService;
import com.binlog.analyzer.service.DictionaryService;
import com.binlog.analyzer.web.SessionSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;

@RestController
@RequestMapping("/api/binlogs")
public class BinlogController {

    private static final Logger log = LoggerFactory.getLogger(BinlogController.class);

    private final DictionaryService dictionaryService;
    private final BinlogDownloadService downloadService;

    public BinlogController(DictionaryService dictionaryService, BinlogDownloadService downloadService) {
        this.dictionaryService = dictionaryService;
        this.downloadService = downloadService;
    }

    @GetMapping
    public List<BinlogFileInfo> list(HttpSession session) {
        return dictionaryService.listBinlogFiles(SessionSupport.requireConnection(session));
    }

    @PostMapping("/download")
    public DownloadTicket download(@RequestBody DownloadRequest request, HttpSession session) {
        return downloadService.prepare(SessionSupport.requireConnection(session),
                request == null ? null : request.file, session);
    }

    @GetMapping("/stream")
    public ResponseEntity<StreamingResponseBody> stream(@RequestParam("token") String token,
                                                        HttpSession session) {
        final BinlogDownloadService.Artifact artifact = downloadService.take(session, token);
        StreamingResponseBody body = outputStream -> {
            try (InputStream input = Files.newInputStream(artifact.path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                }
            } finally {
                try {
                    Files.deleteIfExists(artifact.path);
                } catch (IOException e) {
                    log.debug("Delete downloaded binlog failed: {}", e.getMessage());
                }
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + artifact.fileName + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(artifact.size)
                .body(body);
    }
}
