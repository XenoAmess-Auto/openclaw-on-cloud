#!/bin/bash
# OOC Android 构建脚本

set -e

echo "========================================"
echo "  OOC Android 构建脚本"
echo "========================================"
echo ""

# 检查并安装依赖
echo "[1/5] 检查依赖..."
if [ ! -d "node_modules" ]; then
    echo "  安装依赖..."
    pnpm install
fi

# 构建前端
echo ""
echo "[2/5] 构建前端..."
pnpm run build

# 同步到 Android 项目
echo ""
echo "[3/5] 同步到 Android 项目..."
npx cap sync android

# 构建 APK
echo ""
echo "[4/5] 构建 Android APK..."
cd android

# 检查 gradle wrapper
if [ ! -f "gradlew" ]; then
    echo "  错误: gradlew 不存在"
    exit 1
fi

# 构建 debug APK
./gradlew assembleDebug

# 检查构建结果
if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
    echo ""
    echo "[5/5] 构建成功!"
    echo ""
    echo "APK 位置:"
    echo "  $(pwd)/app/build/outputs/apk/debug/app-debug.apk"
    echo ""
    echo "文件大小:"
    ls -lh app/build/outputs/apk/debug/app-debug.apk | awk '{print "  " $5}'
    echo ""
    echo "========================================"
    echo "  构建完成!"
    echo "========================================"
else
    echo ""
    echo "  错误: APK 构建失败"
    exit 1
fi
