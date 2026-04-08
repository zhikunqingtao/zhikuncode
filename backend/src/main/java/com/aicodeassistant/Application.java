package com.aicodeassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.aicodeassistant.llm.LlmHttpProperties;

/**
 * AI Code Assistant 后端启动类。
 * <p>
 * 技术栈: Spring Boot 3.3+ / Java 21+ / Virtual Threads
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@EnableConfigurationProperties(LlmHttpProperties.class)
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
