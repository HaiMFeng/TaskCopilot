package io.github.haimfeng.taskcopilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TaskCopilotApplication {

    public static void main(String[] args) {
        // 必须在 SpringApplication.run 之前关闭 headless，否则 Spring Boot 会抢先设置
        // java.awt.headless=true，导致后续 java.awt.Robot 截图初始化失败。
        System.setProperty("java.awt.headless", "false");
        SpringApplication.run(TaskCopilotApplication.class, args);
    }

}
