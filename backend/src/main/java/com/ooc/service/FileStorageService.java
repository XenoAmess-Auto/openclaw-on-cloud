package com.ooc.service;

import com.ooc.config.FileProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final FileProperties fileProperties;

    @PostConstruct
    public void init() {
        try {
            Path uploadPath = Paths.get(fileProperties.getUploadDir());
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
                log.info("Created upload directory: {}", uploadPath.toAbsolutePath());
            }
        } catch (IOException e) {
            log.error("Could not create upload directory", e);
        }
    }

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

        try {
            // 生成唯一文件名
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String filename = UUID.randomUUID() + extension;

            // 保存文件
            Path targetLocation = Paths.get(fileProperties.getUploadDir()).resolve(filename);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

            // 确定文件类型
            FileType type = determineFileType(contentType);

            return FileInfo.builder()
                    .filename(filename)
                    .originalName(originalFilename)
                    .url(fileProperties.getUrlPrefix() + "/" + filename)
                    .type(type)
                    .contentType(contentType)
                    .size(file.getSize())
                    .build();

        } catch (IOException e) {
            log.error("Failed to store file", e);
            throw new RuntimeException("文件存储失败", e);
        }
    }

    private FileType determineFileType(String contentType) {
        if (contentType == null) return FileType.FILE;
        if (contentType.startsWith("image/")) return FileType.IMAGE;
        if (contentType.equals("application/pdf")) return FileType.PDF;
        if (contentType.equals("text/plain")) return FileType.TEXT;
        return FileType.FILE;
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
        private FileType type;
        private String contentType;
        private long size;
    }
}
