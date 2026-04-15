package com.aicodeassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.aicodeassistant.llm.LlmHttpProperties;

/**
 * AI Code Assistant 后端启动类。
 * <p>
 * 技术栈: Spring Boot 3.3+ / Java 21+ / Virtual Threads
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@EnableConfigurationProperties(LlmHttpProperties.class)
@EnableScheduling  // ERR-3 fix: 启用定时任务（healthCheck + SseHealthChecker）
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
