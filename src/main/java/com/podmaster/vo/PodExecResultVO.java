package com.podmaster.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Pod 终端执行结果
 */
@Data
@Builder
public class PodExecResultVO {

    private String command;
    private String output;
    private int exitCode;
    private boolean success;
}
