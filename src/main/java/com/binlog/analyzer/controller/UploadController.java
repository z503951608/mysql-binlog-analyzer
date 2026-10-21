package com.binlog.analyzer.controller;

import com.binlog.analyzer.model.UploadInfo;
import com.binlog.analyzer.service.UploadService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadInfo upload(@RequestParam("file") MultipartFile file) {
        return uploadService.save(file);
    }

    @GetMapping
    public List<UploadInfo> list() {
        return uploadService.list();
    }
}
