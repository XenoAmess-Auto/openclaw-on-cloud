package com.ooc.websocket;

import lombok.Data;

/**
 * 附件数据传输对象
 */
@Data
public class Attachment {
    private String type;      // 类型，如 "image"
    private String mimeType;  // MIME 类型，如 "image/png"
    private String content;   // Base64 编码的内容（不含 data URL 前缀）
    private String url;       // 文件 URL（如 /uploads/xxx.png），优先使用
}
