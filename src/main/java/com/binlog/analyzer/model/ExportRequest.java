package com.binlog.analyzer.model;

import java.util.List;

public class ExportRequest {

    public String type = "redo";
    public List<Integer> seqs;
    public String charset = "utf8";
}
