package com.podmaster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Pod Master 启动类
 */
@SpringBootApplication
@EnableScheduling
public class PodMasterApplication {

    public static void main(String[] args) {
        SpringApplication.run(PodMasterApplication.class, args);
    }
}
