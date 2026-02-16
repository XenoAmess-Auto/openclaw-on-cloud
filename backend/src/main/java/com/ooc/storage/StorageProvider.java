package com.ooc.storage;

import com.ooc.service.FileStorageService.FileInfo;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * 文件存储接口 - 支持多种存储后端（本地文件系统、S3等）
 */
public interface StorageProvider {

    /**
     * 存储文件
     * @param file 上传的文件
     * @param key 存储键（通常是文件名或路径）
     * @return 文件信息
     */
    FileInfo store(MultipartFile file, String key);

    /**
     * 获取文件输入流
     * @param key 存储键
     * @return 文件输入流
     */
    InputStream getInputStream(String key);

    /**
     * 获取文件的本地路径（如果支持）
     * @param key 存储键
     * @return 本地路径，如果不支持则返回null
     */
    Path getLocalPath(String key);

    /**
     * 删除文件
     * @param key 存储键
     * @return 是否成功
     */
    boolean delete(String key);

    /**
     * 检查文件是否存在
     * @param key 存储键
     * @return 是否存在
     */
    boolean exists(String key);

    /**
     * 获取访问URL
     * @param key 存储键
     * @return 访问URL
     */
    String getUrl(String key);

    /**
     * 获取存储类型名称
     * @return 存储类型
     */
    String getStorageType();
}
