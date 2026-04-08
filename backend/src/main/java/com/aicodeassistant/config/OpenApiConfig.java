package com.aicodeassistant.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * SpringDoc OpenAPI configuration.
 * Accessible at /swagger-ui.html after application startup.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("AI Code Assistant API")
                        .version("1.0.0")
                        .description("AI Code Assistant Backend REST API Documentation")
                        .contact(new Contact()
                                .name("AI Code Assistant Team")
                                .email("team@aicodeassistant.com"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local Development"),
                        new Server().url("https://api.aicodeassistant.com").description("Production")));
    }
}
