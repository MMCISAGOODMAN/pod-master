package com.podmaster.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * AI 日志诊断结果 VO
 */
@Data
@Builder
public class LogDiagnosisVO {

    private String podName;
    private String namespace;
    private String summary;
    private String faultType;
    private String severity;
    private List<LogLine> logLines;
    private List<String> errorStacks;
    private List<Suggestion> suggestions;
    private boolean diagnosed;

    @Data
    @Builder
    public static class LogLine {
        private int lineNumber;
        private String content;
        private String level;
        private long timestamp;
    }

    @Data
    @Builder
    public static class Suggestion {
        private String title;
        private String description;
        private String priority;
    }
}
