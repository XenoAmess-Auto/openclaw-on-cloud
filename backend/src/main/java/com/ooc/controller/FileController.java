package com.ooc.controller;

import com.ooc.service.FileStorageService;
import com.ooc.service.FileStorageService.FileInfo;
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
    public ResponseEntity<FileInfo> uploadFile(@RequestParam("file") MultipartFile file) {
        log.info("Uploading file: {}, size: {}", file.getOriginalFilename(), file.getSize());
        FileInfo fileInfo = fileStorageService.store(file);

        // 将 URL 替换为经过 8081 端口包装的接口地址
        String wrapperUrl = "/api/files/" + fileInfo.getFilename();
        FileInfo result = FileInfo.builder()
                .filename(fileInfo.getFilename())
                .originalName(fileInfo.getOriginalName())
                .url(wrapperUrl)
                .localPath(fileInfo.getLocalPath())
                .type(fileInfo.getType())
                .contentType(fileInfo.getContentType())
                .size(fileInfo.getSize())
                .build();

        log.info("File uploaded successfully: {} -> {}", fileInfo.getFilename(), wrapperUrl);
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
