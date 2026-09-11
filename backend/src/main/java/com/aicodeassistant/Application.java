package com.aicodeassistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.aicodeassistant.llm.LlmHttpProperties;
import com.aicodeassistant.llm.LlmProvidersProperties;

import java.nio.file.Path;

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

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
        logResolvedLogDirectory();
    }

    /**
     * 启动后打印最终解析的日志目录，便于运维确认日志落点。
     * 解析规则与 log4j2.xml 的 APP_LOG_DIR 一致：env LOG_DIR 优先，回退 user.dir/log。
     */
    private static void logResolvedLogDirectory() {
        String envLogDir = System.getenv("LOG_DIR");
        boolean fromEnv = envLogDir != null && !envLogDir.isBlank();
        Path logDir = fromEnv
                ? Path.of(envLogDir)
                : Path.of(System.getProperty("user.dir"), "log");
        log.info("Resolved log directory: {} (source={})",
                logDir.toAbsolutePath(), fromEnv ? "env LOG_DIR" : "user.dir/log fallback");
    }
}
