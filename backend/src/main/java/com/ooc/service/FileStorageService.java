package com.ooc.service;

import com.ooc.config.FileProperties;
import com.ooc.storage.StorageProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Arrays;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final FileProperties fileProperties;
    private final StorageProvider storageProvider;

    public FileInfo store(MultipartFile file) {
        // 验证文件类型
        String contentType = file.getContentType();
        if (contentType == null || !Arrays.asList(fileProperties.getAllowedTypes()).contains(contentType)) {
            throw new RuntimeException("不支持的文件类型: " + contentType);
        }

        // 验证文件大小
        long maxSizeBytes = fileProperties.getMaxSize() * 1024 * 1024;
        if (file.getSize() > maxSizeBytes) {
            throw new RuntimeException("文件大小超过限制: " + fileProperties.getMaxSize() + "MB");
        }

        // 生成唯一文件名
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String key = UUID.randomUUID() + extension;

        // 使用存储提供者保存文件
        FileInfo fileInfo = storageProvider.store(file, key);

        log.info("[FileStorage] File stored via {}: {}, original: {}, size: {}",
                storageProvider.getStorageType(), key, originalFilename, file.getSize());

        return fileInfo;
    }

    public InputStream getInputStream(String key) {
        return storageProvider.getInputStream(key);
    }

    public String getLocalPath(String key) {
        if (storageProvider.getLocalPath(key) != null) {
            return storageProvider.getLocalPath(key).toString();
        }
        return null;
    }

    public boolean delete(String key) {
        return storageProvider.delete(key);
    }

    public boolean exists(String key) {
        return storageProvider.exists(key);
    }

    public String getUrl(String key) {
        return storageProvider.getUrl(key);
    }

    public String getStorageType() {
        return storageProvider.getStorageType();
    }

    public enum FileType {
        IMAGE, PDF, TEXT, FILE
    }

    @lombok.Data
    @lombok.Builder
    public static class FileInfo {
        private String filename;
        private String originalName;
        private String url;
        private String localPath;
        private FileType type;
        private String contentType;
        private long size;
    }
}
