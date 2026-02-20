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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地文件系统存储实现
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.storage-type", havingValue = "local", matchIfMissing = true)
public class LocalStorageProvider implements StorageProvider {

    private final FileProperties fileProperties;

    @PostConstruct
    public void init() {
        try {
            Path uploadPath = Paths.get(fileProperties.getUploadDir());
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
                log.info("[LocalStorage] Created upload directory: {}", uploadPath.toAbsolutePath());
            }
        } catch (IOException e) {
            log.error("[LocalStorage] Could not create upload directory", e);
        }
    }

    @Override
    public FileInfo store(MultipartFile file, String key) {
        try {
            Path targetLocation = Paths.get(fileProperties.getUploadDir()).resolve(key);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

            Path absolutePath = targetLocation.toAbsolutePath().normalize();
            String contentType = file.getContentType();
            if (contentType == null) {
                contentType = "application/octet-stream";
            }

            log.info("[LocalStorage] File stored: {}", absolutePath);

            return FileInfo.builder()
                    .filename(key)
                    .originalName(file.getOriginalFilename())
                    .url(getUrl(key))
                    .localPath(absolutePath.toString())
                    .type(determineFileType(contentType))
                    .contentType(contentType)
                    .size(file.getSize())
                    .build();

        } catch (IOException e) {
            log.error("[LocalStorage] Failed to store file: {}", key, e);
            throw new RuntimeException("文件存储失败", e);
        }
    }

    @Override
    public InputStream getInputStream(String key) {
        try {
            Path filePath = Paths.get(fileProperties.getUploadDir()).resolve(key);
            return Files.newInputStream(filePath);
        } catch (IOException e) {
            log.error("[LocalStorage] Failed to get input stream: {}", key, e);
            throw new RuntimeException("无法读取文件", e);
        }
    }

    @Override
    public Path getLocalPath(String key) {
        return Paths.get(fileProperties.getUploadDir()).resolve(key).toAbsolutePath().normalize();
    }

    @Override
    public boolean delete(String key) {
        try {
            Path filePath = Paths.get(fileProperties.getUploadDir()).resolve(key);
            return Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.error("[LocalStorage] Failed to delete file: {}", key, e);
            return false;
        }
    }

    @Override
    public boolean exists(String key) {
        Path filePath = Paths.get(fileProperties.getUploadDir()).resolve(key);
        return Files.exists(filePath);
    }

    @Override
    public String getUrl(String key) {
        return fileProperties.getUrlPrefix() + "/" + key;
    }

    @Override
    public String getStorageType() {
        return "local";
    }

    private FileType determineFileType(String contentType) {
        if (contentType == null) return FileType.FILE;
        if (contentType.startsWith("image/")) return FileType.IMAGE;
        if (contentType.equals("application/pdf")) return FileType.PDF;
        if (contentType.equals("text/plain")) return FileType.TEXT;
        return FileType.FILE;
    }
}
