package com.ooc.storage;

import com.ooc.config.FileProperties;
import com.ooc.service.FileStorageService.FileInfo;
import com.ooc.service.FileStorageService.FileType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;

/**
 * AWS S3 兼容存储实现（支持 AWS S3、MinIO、Cloudflare R2 等）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.storage-type", havingValue = "s3")
public class S3StorageProvider implements StorageProvider {

    private final FileProperties fileProperties;
    private S3Client s3Client;

    @PostConstruct
    public void init() {
        try {
            S3Config s3Config = fileProperties.getS3();
            if (s3Config == null || s3Config.getEndpoint() == null) {
                throw new RuntimeException("S3 configuration is missing. Please configure file.s3.endpoint");
            }

            AwsBasicCredentials credentials = AwsBasicCredentials.create(
                    s3Config.getAccessKey(),
                    s3Config.getSecretKey()
            );

            // 构建 S3 客户端
            software.amazon.awssdk.services.s3.S3ClientBuilder builder = S3Client.builder()
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .region(Region.of(s3Config.getRegion()));

            // 配置自定义端点（用于 MinIO、R2 等兼容服务）
            if (s3Config.getEndpoint() != null && !s3Config.getEndpoint().isEmpty()) {
                builder.endpointOverride(URI.create(s3Config.getEndpoint()));
                // 路径样式访问（MinIO 等需要）
                builder.serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s3Config.isPathStyleAccess())
                        .build());
            }

            s3Client = builder.build();
            log.info("[S3Storage] S3 client initialized. Endpoint: {}, Bucket: {}",
                    s3Config.getEndpoint(), s3Config.getBucket());

            // 检查并创建 bucket（如果不存在）
            ensureBucketExists(s3Config.getBucket());

        } catch (Exception e) {
            log.error("[S3Storage] Failed to initialize S3 client", e);
            throw new RuntimeException("S3 存储初始化失败", e);
        }
    }

    @Override
    public FileInfo store(MultipartFile file, String key) {
        try {
            S3Config s3Config = fileProperties.getS3();
            String contentType = file.getContentType();

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3Config.getBucket())
                    .key(key)
                    .contentType(contentType)
                    .contentLength(file.getSize())
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

            log.info("[S3Storage] File uploaded to S3: s3://{}/{}", s3Config.getBucket(), key);

            return FileInfo.builder()
                    .filename(key)
                    .originalName(file.getOriginalFilename())
                    .url(getUrl(key))
                    .localPath(null) // S3 文件没有本地路径
                    .type(determineFileType(contentType))
                    .contentType(contentType)
                    .size(file.getSize())
                    .build();

        } catch (IOException e) {
            log.error("[S3Storage] Failed to store file: {}", key, e);
            throw new RuntimeException("S3 文件上传失败", e);
        }
    }

    @Override
    public InputStream getInputStream(String key) {
        S3Config s3Config = fileProperties.getS3();
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(s3Config.getBucket())
                .key(key)
                .build();

        return s3Client.getObject(getRequest);
    }

    @Override
    public Path getLocalPath(String key) {
        // S3 存储的文件没有本地路径
        return null;
    }

    @Override
    public boolean delete(String key) {
        try {
            S3Config s3Config = fileProperties.getS3();
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(s3Config.getBucket())
                    .key(key)
                    .build();

            s3Client.deleteObject(deleteRequest);
            log.info("[S3Storage] File deleted from S3: s3://{}/{}", s3Config.getBucket(), key);
            return true;

        } catch (Exception e) {
            log.error("[S3Storage] Failed to delete file: {}", key, e);
            return false;
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            S3Config s3Config = fileProperties.getS3();
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(s3Config.getBucket())
                    .key(key)
                    .build();

            s3Client.headObject(headRequest);
            return true;

        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getUrl(String key) {
        S3Config s3Config = fileProperties.getS3();

        // 如果配置了自定义 CDN URL，使用它
        if (s3Config.getCdnUrl() != null && !s3Config.getCdnUrl().isEmpty()) {
            String cdnUrl = s3Config.getCdnUrl();
            // 移除末尾的斜杠
            if (cdnUrl.endsWith("/")) {
                cdnUrl = cdnUrl.substring(0, cdnUrl.length() - 1);
            }
            return cdnUrl + "/" + key;
        }

        // 否则使用 S3 直接访问 URL
        if (s3Config.getEndpoint() != null && s3Config.getEndpoint().contains("amazonaws.com")) {
            // AWS S3 标准 URL
            return String.format("https://%s.s3.%s.amazonaws.com/%s",
                    s3Config.getBucket(), s3Config.getRegion(), key);
        } else {
            // 自定义 S3 兼容服务（MinIO 等）
            String endpoint = s3Config.getEndpoint();
            if (endpoint.endsWith("/")) {
                endpoint = endpoint.substring(0, endpoint.length() - 1);
            }
            return endpoint + "/" + s3Config.getBucket() + "/" + key;
        }
    }

    @Override
    public String getStorageType() {
        return "s3";
    }

    /**
     * 检查 bucket 是否存在，不存在则自动创建
     */
    private void ensureBucketExists(String bucketName) {
        try {
            // 尝试访问 bucket
            HeadBucketRequest headBucketRequest = HeadBucketRequest.builder()
                    .bucket(bucketName)
                    .build();
            s3Client.headBucket(headBucketRequest);
            log.info("[S3Storage] Bucket '{}' exists", bucketName);
        } catch (NoSuchBucketException e) {
            // Bucket 不存在，创建它
            log.warn("[S3Storage] Bucket '{}' does not exist, creating...", bucketName);
            try {
                CreateBucketRequest createBucketRequest = CreateBucketRequest.builder()
                        .bucket(bucketName)
                        .build();
                s3Client.createBucket(createBucketRequest);
                log.info("[S3Storage] Bucket '{}' created successfully", bucketName);
            } catch (Exception createException) {
                log.error("[S3Storage] Failed to create bucket '{}': {}", bucketName, createException.getMessage());
                throw new RuntimeException("无法创建 S3 bucket: " + bucketName, createException);
            }
        } catch (Exception e) {
            log.error("[S3Storage] Error checking bucket existence '{}': {}", bucketName, e.getMessage());
            throw new RuntimeException("检查 S3 bucket 状态时出错: " + bucketName, e);
        }
    }

    private FileType determineFileType(String contentType) {
        if (contentType == null) return FileType.FILE;
        if (contentType.startsWith("image/")) return FileType.IMAGE;
        if (contentType.equals("application/pdf")) return FileType.PDF;
        if (contentType.equals("text/plain")) return FileType.TEXT;
        return FileType.FILE;
    }
}
