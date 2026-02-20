package com.ooc.config;

import com.ooc.storage.S3Config;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "file")
public class FileProperties {

    /**
     * 存储类型: local 或 s3
     */
    private String storageType = "local";

    /**
     * 本地存储配置
     */
    private String uploadDir = "./uploads";
    private String[] allowedTypes = {"image/jpeg", "image/png", "image/gif", "image/webp", "application/pdf", "text/plain"};
    private long maxSize = 100;
    private String urlPrefix = "/uploads";

    /**
     * S3 存储配置
     */
    private S3Config s3;

    /**
     * 外部可访问的基础 URL，用于生成文件下载链接
     * 例如: http://3.94.174.102:8081 或 https://ooc.example.com
     * 如果不配置，将尝试自动检测
     */
    private String externalBaseUrl = "";
}
