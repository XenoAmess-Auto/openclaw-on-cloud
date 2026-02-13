package com.ooc.controller;

import com.ooc.service.FileStorageService;
import com.ooc.service.FileStorageService.FileInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
        log.info("File uploaded successfully: {}", fileInfo.getUrl());
        return ResponseEntity.ok(fileInfo);
    }
}
