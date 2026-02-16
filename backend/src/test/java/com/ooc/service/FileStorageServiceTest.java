package com.ooc.service;

import com.ooc.config.FileProperties;
import com.ooc.storage.LocalStorageProvider;
import com.ooc.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileStorageServiceTest {

    @Mock
    private FileProperties fileProperties;

    private FileStorageService fileStorageService;
    private StorageProvider storageProvider;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        when(fileProperties.getUploadDir()).thenReturn(tempDir.toString());
        when(fileProperties.getAllowedTypes()).thenReturn(new String[]{
                "image/jpeg", "image/png", "image/gif", "image/webp",
                "application/pdf", "text/plain"
        });
        when(fileProperties.getMaxSize()).thenReturn(10L);
        when(fileProperties.getUrlPrefix()).thenReturn("/uploads");

        // 创建真实的 LocalStorageProvider 用于测试
        storageProvider = new LocalStorageProvider(fileProperties);

        // 创建 FileStorageService
        fileStorageService = new FileStorageService(fileProperties, storageProvider);
    }

    @Test
    void store_WithValidImage_ShouldStoreSuccessfully() throws IOException {
        // Given
        byte[] content = "fake-image-content".getBytes();
        MultipartFile file = new MockMultipartFile(
                "file",
                "test-image.png",
                "image/png",
                content
        );

        // When
        FileStorageService.FileInfo result = fileStorageService.store(file);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getOriginalName()).isEqualTo("test-image.png");
        assertThat(result.getType()).isEqualTo(FileStorageService.FileType.IMAGE);
        assertThat(result.getContentType()).isEqualTo("image/png");
        assertThat(result.getSize()).isEqualTo(content.length);
        assertThat(result.getUrl()).startsWith("/uploads/");
        assertThat(result.getUrl()).endsWith(".png");
        assertThat(result.getLocalPath()).isNotNull();

        // Verify file was created
        Path storedFile = Path.of(result.getLocalPath());
        assertThat(Files.exists(storedFile)).isTrue();
    }

    @Test
    void store_WithValidPdf_ShouldStoreSuccessfully() throws IOException {
        // Given
        byte[] content = "fake-pdf-content".getBytes();
        MultipartFile file = new MockMultipartFile(
                "file",
                "document.pdf",
                "application/pdf",
                content
        );

        // When
        FileStorageService.FileInfo result = fileStorageService.store(file);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getOriginalName()).isEqualTo("document.pdf");
        assertThat(result.getType()).isEqualTo(FileStorageService.FileType.PDF);
        assertThat(result.getContentType()).isEqualTo("application/pdf");
    }

    @Test
    void store_WithTextFile_ShouldStoreSuccessfully() throws IOException {
        // Given
        byte[] content = "Hello World".getBytes();
        MultipartFile file = new MockMultipartFile(
                "file",
                "readme.txt",
                "text/plain",
                content
        );

        // When
        FileStorageService.FileInfo result = fileStorageService.store(file);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getOriginalName()).isEqualTo("readme.txt");
        assertThat(result.getType()).isEqualTo(FileStorageService.FileType.TEXT);
    }

    @Test
    void store_WithUnsupportedType_ShouldThrowException() {
        // Given
        MultipartFile file = new MockMultipartFile(
                "file",
                "malware.exe",
                "application/x-msdownload",
                "fake-exe".getBytes()
        );

        // When & Then
        assertThatThrownBy(() -> fileStorageService.store(file))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("不支持的文件类型");
    }

    @Test
    void store_WithOversizedFile_ShouldThrowException() {
        // Given - 15MB file
        byte[] content = new byte[15 * 1024 * 1024];
        MultipartFile file = new MockMultipartFile(
                "file",
                "large-image.png",
                "image/png",
                content
        );

        // When & Then
        assertThatThrownBy(() -> fileStorageService.store(file))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("文件大小超过限制");
    }

    @Test
    void store_WithNullContentType_ShouldThrowException() {
        // Given
        MultipartFile file = new MockMultipartFile(
                "file",
                "unknown",
                null,
                "content".getBytes()
        );

        // When & Then
        assertThatThrownBy(() -> fileStorageService.store(file))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("不支持的文件类型");
    }

    @Test
    void store_WithoutExtension_ShouldGenerateFilenameWithoutExtension() throws IOException {
        // Given
        MultipartFile file = new MockMultipartFile(
                "file",
                "noextension",
                "image/png",
                "content".getBytes()
        );

        // When
        FileStorageService.FileInfo result = fileStorageService.store(file);

        // Then
        assertThat(result.getFilename()).doesNotContain(".");
    }

    @Test
    void store_WithMultipleDotsInName_ShouldPreserveLastExtension() throws IOException {
        // Given
        MultipartFile file = new MockMultipartFile(
                "file",
                "archive.tar.gz",
                "application/gzip",
                "content".getBytes()
        );

        // Override allowed types to include gzip for this test
        when(fileProperties.getAllowedTypes()).thenReturn(new String[]{
                "image/jpeg", "image/png", "image/gif", "image/webp",
                "application/pdf", "text/plain", "application/gzip"
        });

        // When
        FileStorageService.FileInfo result = fileStorageService.store(file);

        // Then
        assertThat(result.getFilename()).endsWith(".gz");
    }

    @Test
    void fileType_ShouldReturnCorrectType() {
        // These are effectively tested through the store tests above
        // But we can verify the enum values exist
        assertThat(FileStorageService.FileType.values()).contains(
                FileStorageService.FileType.IMAGE,
                FileStorageService.FileType.PDF,
                FileStorageService.FileType.TEXT,
                FileStorageService.FileType.FILE
        );
    }

    @Test
    void getStorageType_ShouldReturnLocal() {
        assertThat(fileStorageService.getStorageType()).isEqualTo("local");
    }
}
