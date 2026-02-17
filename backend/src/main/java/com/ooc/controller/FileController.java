package com.ooc.controller;

import com.ooc.service.FileStorageService;
import com.ooc.service.FileStorageService.FileInfo;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    @PostMapping("/upload")
    public ResponseEntity<FileInfo> uploadFile(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        log.info("Uploading file: {}, size: {}", file.getOriginalFilename(), file.getSize());
        FileInfo fileInfo = fileStorageService.store(file);

        // 返回相对路径，让前端根据配置的 baseUrl 拼接完整 URL
        // 这样可以避免安卓端和网页端使用不同后端地址时的问题
        String relativeUrl = "/api/files/" + fileInfo.getFilename();

        FileInfo result = FileInfo.builder()
                .filename(fileInfo.getFilename())
                .originalName(fileInfo.getOriginalName())
                .url(relativeUrl)
                .localPath(fileInfo.getLocalPath())
                .type(fileInfo.getType())
                .contentType(fileInfo.getContentType())
                .size(fileInfo.getSize())
                .build();

        log.info("File uploaded successfully: {} -> {}", fileInfo.getFilename(), relativeUrl);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{key}")
    public ResponseEntity<InputStreamResource> getFile(@PathVariable String key) {
        log.debug("Fetching file: {}", key);

        if (!fileStorageService.exists(key)) {
            return ResponseEntity.notFound().build();
        }

        InputStream inputStream = fileStorageService.getInputStream(key);
        InputStreamResource resource = new InputStreamResource(inputStream);

        // 根据文件扩展名推断内容类型
        String contentType = determineContentType(key);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + key + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

    private String determineContentType(String key) {
        if (key.endsWith(".png")) return "image/png";
        if (key.endsWith(".jpg") || key.endsWith(".jpeg")) return "image/jpeg";
        if (key.endsWith(".gif")) return "image/gif";
        if (key.endsWith(".webp")) return "image/webp";
        if (key.endsWith(".pdf")) return "application/pdf";
        if (key.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }
}
