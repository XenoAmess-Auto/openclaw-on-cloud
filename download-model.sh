#!/bin/bash
# 下载 Whisper 模型文件脚本
# 需要在可以访问 Hugging Face 的环境中运行

MODEL_DIR="frontend/public/models"
mkdir -p "$MODEL_DIR"

echo "正在下载 Whisper 模型文件..."
echo "注意: 如果直接下载失败，请使用代理"

# 方法1: 直接下载
download_direct() {
    echo "尝试从 Hugging Face 直接下载..."
    curl -L -o "$MODEL_DIR/model_quantized.onnx" \
        "https://huggingface.co/Xenova/whisper-base/resolve/main/onnx/model_quantized.onnx"
}

# 方法2: 使用代理
download_with_proxy() {
    echo "尝试使用代理下载..."
    # 设置代理环境变量
    export HTTP_PROXY="${HTTP_PROXY:-http://127.0.0.1:7890}"
    export HTTPS_PROXY="${HTTPS_PROXY:-http://127.0.0.1:7890}"
    curl -L -o "$MODEL_DIR/model_quantized.onnx" \
        "https://huggingface.co/Xenova/whisper-base/resolve/main/onnx/model_quantized.onnx"
}

# 方法3: 使用 Python huggingface_hub
download_with_python() {
    echo "尝试使用 Python 下载..."
    python3 << 'PYEOF'
from huggingface_hub import hf_hub_download
import os

os.makedirs("frontend/public/models", exist_ok=True)

# 下载模型文件
model_path = hf_hub_download(
    repo_id="Xenova/whisper-base",
    filename="onnx/model_quantized.onnx",
    local_dir="frontend/public/models",
    local_dir_use_symlinks=False
)
print(f"Downloaded to: {model_path}")
PYEOF
}

# 检查文件大小
 check_file() {
    local file="$MODEL_DIR/model_quantized.onnx"
    if [ -f "$file" ]; then
        local size=$(stat -f%z "$file" 2>/dev/null || stat -c%s "$file" 2>/dev/null)
        if [ "$size" -gt 1000000 ]; then
            echo "✓ 模型文件下载成功: $size bytes"
            return 0
        fi
    fi
    return 1
}

# 执行下载
if check_file; then
    echo "模型文件已存在"
    exit 0
fi

# 尝试各种方法
download_direct && check_file && exit 0
download_with_proxy && check_file && exit 0
download_with_python && check_file && exit 0

echo "下载失败，请手动下载:"
echo "1. 访问 https://huggingface.co/Xenova/whisper-base/tree/main/onnx"
echo "2. 下载 model_quantized.onnx (约 30MB)"
echo "3. 放到 frontend/public/models/ 目录"
exit 1
