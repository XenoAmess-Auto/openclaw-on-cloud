package com.ooc.storage;

import lombok.Data;

/**
 * S3 存储配置
 */
@Data
public class S3Config {

    /**
     * S3 服务端点
     * AWS S3: https://s3.amazonaws.com 或区域特定端点
     * MinIO: http://localhost:9000
     * Cloudflare R2: https://{account-id}.r2.cloudflarestorage.com
     */
    private String endpoint;

    /**
     * S3 区域
     * AWS S3: us-east-1, ap-northeast-1 等
     * MinIO: us-east-1 (默认)
     */
    private String region = "us-east-1";

    /**
     * 存储桶名称
     */
    private String bucket;

    /**
     * Access Key
     */
    private String accessKey;

    /**
     * Secret Key
     */
    private String secretKey;

    /**
     * 是否使用路径样式访问（MinIO 等需要设置为 true）
     */
    private boolean pathStyleAccess = true;

    /**
     * 自定义 CDN URL（可选）
     * 例如：https://cdn.example.com 或 https://your-bucket.r2.dev
     */
    private String cdnUrl;
}
