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
    private long maxSize = 10;
    private String urlPrefix = "/uploads";

    /**
     * S3 存储配置
     */
    private S3Config s3;
}
