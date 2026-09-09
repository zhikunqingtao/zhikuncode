package com.aicodeassistant.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.DispatcherServlet;

/** Keeps browser-selected files on the raw request-body path for every MIME type. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MultipartProperties.class)
public class RawFileUploadWebConfig {
    public static final String LOCAL_FILE_UPLOAD_PATH = "/api/oss/local-files";

    @Bean(name = DispatcherServlet.MULTIPART_RESOLVER_BEAN_NAME)
    public MultipartResolver multipartResolver(MultipartProperties properties) {
        StandardServletMultipartResolver resolver =
                new StandardServletMultipartResolver() {
                    @Override
                    public boolean isMultipart(HttpServletRequest request) {
                        return !isRawLocalFileUpload(request)
                                && super.isMultipart(request);
                    }
                };
        resolver.setResolveLazily(properties.isResolveLazily());
        resolver.setStrictServletCompliance(
                properties.isStrictServletCompliance());
        return resolver;
    }

    public static boolean isRawLocalFileUpload(HttpServletRequest request) {
        if (request == null
                || !"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String contextPath = request.getContextPath() == null
                ? "" : request.getContextPath();
        return (contextPath + LOCAL_FILE_UPLOAD_PATH)
                .equals(request.getRequestURI());
    }
}
