package com.ooc.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final FileProperties fileProperties;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path uploadPath = Paths.get(fileProperties.getUploadDir()).toAbsolutePath().normalize();
        registry.addResourceHandler(fileProperties.getUrlPrefix() + "/**")
                .addResourceLocations("file:" + uploadPath.toString() + "/");
    }
}
