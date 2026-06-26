package com.example.lms.config;

import com.example.lms.common.ReqLogInterceptor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;



@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebMvcConfig.class);

    private final ReqLogInterceptor reqLogInterceptor;

    @Value("${lms.upload-dir:uploads}")
    private String uploadDir;

    @Value("${lms.upload-public-prefix:/uploads/}")
    private String uploadPublicPrefix;

    /**
     * 모든 요청에 대해 ReqLogInterceptor를 등록합니다.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(reqLogInterceptor)
                .addPathPatterns("/**");
    }

    /**
     * 정적 뷰 매핑을 설정합니다.
     * "/"      → templates/index.html
     * "/chat-ui" → templates/chat-ui.html
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("index");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        try {
            String prefix = normalizePublicPrefix(uploadPublicPrefix);
            String location = Path.of(uploadDir).toAbsolutePath().normalize().toUri().toString();
            registry.addResourceHandler(prefix + "**")
                    .addResourceLocations(location);
        } catch (InvalidPathException ex) {
            log.warn("[AWX][upload] static upload handler disabled disabledReason=invalid_upload_dir exceptionType={}",
                    ex.getClass().getSimpleName());
        }
    }

    private static String normalizePublicPrefix(String value) {
        String prefix = (value == null || value.isBlank()) ? "/uploads/" : value.trim();
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        return prefix;
    }
}
