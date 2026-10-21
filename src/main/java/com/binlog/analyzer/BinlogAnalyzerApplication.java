package com.binlog.analyzer;

import com.binlog.analyzer.config.BinlogProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(BinlogProperties.class)
public class BinlogAnalyzerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BinlogAnalyzerApplication.class, args);
    }
}
