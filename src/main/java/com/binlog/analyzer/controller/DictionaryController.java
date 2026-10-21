package com.binlog.analyzer.controller;

import com.binlog.analyzer.model.ConnectionContext;
import com.binlog.analyzer.model.ConnectionInfo;
import com.binlog.analyzer.model.ConnectionRequest;
import com.binlog.analyzer.service.DictionaryService;
import com.binlog.analyzer.web.ApiException;
import com.binlog.analyzer.web.SessionSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.util.List;

@RestController
@RequestMapping("/api/dictionary")
public class DictionaryController {

    private final DictionaryService dictionaryService;

    public DictionaryController(DictionaryService dictionaryService) {
        this.dictionaryService = dictionaryService;
    }

    @PostMapping
    public ConnectionInfo connect(@RequestBody ConnectionRequest request, HttpSession session) {
        ConnectionContext context = dictionaryService.connect(request);
        session.setAttribute(SessionSupport.CONNECTION_ATTRIBUTE, context);
        return context.toInfo();
    }

    @GetMapping("/status")
    public ConnectionInfo status(HttpSession session) {
        Object value = session.getAttribute(SessionSupport.CONNECTION_ATTRIBUTE);
        if (value instanceof ConnectionContext) {
            return ((ConnectionContext) value).toInfo();
        }
        return new ConnectionInfo();
    }

    @GetMapping("/tables")
    public List<String> tables(@RequestParam("schema") String schema, HttpSession session) {
        if (schema == null || schema.trim().isEmpty() || schema.length() > 128) {
            throw new ApiException("请选择要分析的数据库");
        }
        return dictionaryService.listTables(SessionSupport.requireConnection(session), schema.trim());
    }

    @PostMapping("/disconnect")
    public ConnectionInfo disconnect(HttpSession session) {
        session.removeAttribute(SessionSupport.CONNECTION_ATTRIBUTE);
        return new ConnectionInfo();
    }
}
