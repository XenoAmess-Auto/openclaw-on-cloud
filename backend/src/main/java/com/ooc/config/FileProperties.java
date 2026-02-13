package com.ooc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "file")
public class FileProperties {
    // 上传目录
    private String uploadDir = "./uploads";
    // 允许的文件类型
    private String[] allowedTypes = {"image/jpeg", "image/png", "image/gif", "image/webp", "application/pdf", "text/plain"};
    // 最大文件大小 (MB)
    private long maxSize = 10;
    // 访问URL前缀
    private String urlPrefix = "/uploads";
}
