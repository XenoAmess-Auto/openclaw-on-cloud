package com.ooc.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final FileProperties fileProperties;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 只在本地存储模式下注册静态资源处理器
        if ("local".equalsIgnoreCase(fileProperties.getStorageType())) {
            Path uploadPath = Paths.get(fileProperties.getUploadDir()).toAbsolutePath().normalize();
            registry.addResourceHandler(fileProperties.getUrlPrefix() + "/**")
                    .addResourceLocations("file:" + uploadPath.toString() + "/");
            log.info("[WebConfig] Registered local resource handler: {} -> {}",
                    fileProperties.getUrlPrefix(), uploadPath);
        } else {
            log.info("[WebConfig] S3 storage mode - skipping local resource handler registration");
        }
    }
}
