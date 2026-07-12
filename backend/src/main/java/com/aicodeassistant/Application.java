package com.aicodeassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.aicodeassistant.llm.LlmHttpProperties;
import com.aicodeassistant.llm.LlmProvidersProperties;

/**
 * AI Code Assistant 后端启动类。
 * <p>
 * 技术栈: Spring Boot 3.4+ / Java 21+ / Virtual Threads
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@EnableConfigurationProperties({LlmHttpProperties.class, LlmProvidersProperties.class})
@EnableScheduling  // ERR-3 fix: 启用定时任务（healthCheck + SseHealthChecker）
@EnableAsync
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
