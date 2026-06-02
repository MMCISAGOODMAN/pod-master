package com.podmaster.util;

import io.fabric8.kubernetes.client.dsl.Loggable;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * K8s Pod 日志工具
 */
public final class LogUtils {

    private LogUtils() {
    }

    /**
     * 获取 Pod 尾部日志（Fabric8 6.x 通过 getLog 后截取行数）
     */
    public static String tailLog(Loggable loggable, int tailLines) {
        String fullLog = loggable.getLog();
        if (fullLog == null || fullLog.isBlank()) {
            return "";
        }
        if (tailLines <= 0) {
            return fullLog;
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(fullLog))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (Exception e) {
            return fullLog;
        }
        if (lines.size() <= tailLines) {
            return String.join("\n", lines);
        }
        return lines.subList(lines.size() - tailLines, lines.size())
                .stream()
                .collect(Collectors.joining("\n"));
    }
}
