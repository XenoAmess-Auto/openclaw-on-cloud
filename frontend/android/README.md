# OOC Android 应用

使用 Capacitor 将 OOC 前端打包为 Android 应用。

## 前置要求

1. **Node.js** 18+ 和 pnpm
2. **Android Studio** (用于构建和调试)
3. **JDK** 17+ (Android Studio 自带)
4. **Android SDK** (通过 Android Studio 安装)

## 快速开始

### 方法一：使用构建脚本（推荐）

```bash
# 一键构建 Debug APK
./scripts/build-android.sh
```

构建完成后，APK 位于：
- `android/app/build/outputs/apk/debug/app-debug.apk`

### 方法二：手动构建

```bash
# 1. 安装依赖
pnpm install

# 2. 构建前端
pnpm run build

# 3. 同步到 Android 项目
npx cap sync android

# 4. 构建 APK
cd android
./gradlew assembleDebug
```

### 方法三：使用 Android Studio

```bash
# 同步后打开 Android Studio
npx cap sync android
npx cap open android
```

然后在 Android Studio 中点击 "Build" → "Build Bundle(s) / APK(s)" → "Build APK(s)"

## 常用命令

| 命令 | 说明 |
|------|------|
| `pnpm android:sync` | 同步前端代码到 Android 项目 |
| `pnpm android:build` | 构建 Android APK |
| `pnpm android:open` | 在 Android Studio 中打开项目 |
| `pnpm android:run` | 在连接的设备上运行应用 |
| `pnpm mobile:build` | 完整构建流程（前端+同步+构建） |

## 项目结构

```
frontend/
├── android/              # Android 原生项目
│   ├── app/src/main/     # 应用源码
│   │   ├── AndroidManifest.xml
│   │   ├── assets/public/  # 前端构建产物（自动生成）
│   │   └── res/          # 资源文件
│   └── build.gradle      # 构建配置
├── capacitor.config.ts   # Capacitor 配置
└── dist/                 # 前端构建输出
```

## 配置说明

### 后端地址配置

应用支持在登录/设置页面配置后端服务器地址。配置保存在本地存储中。

默认后端地址：`http://<设备IP>:8081`

### Capacitor 配置

编辑 `capacitor.config.ts`：

```typescript
const config: CapacitorConfig = {
  appId: 'com.ooc.app',
  appName: 'OOC',
  webDir: 'dist',
  server: {
    androidScheme: 'https',
    cleartext: true  // 允许 HTTP 通信
  },
  android: {
    allowMixedContent: true  // 允许混合内容
  }
}
```

## 发布构建

### 生成签名密钥

```bash
keytool -genkey -v -keystore ooc-release-key.keystore -alias ooc -keyalg RSA -keysize 2048 -validity 10000
```

### 配置签名

编辑 `android/app/build.gradle`：

```gradle
android {
    signingConfigs {
        release {
            storeFile file("ooc-release-key.keystore")
            storePassword "密码"
            keyAlias "ooc"
            keyPassword "密码"
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
        }
    }
}
```

### 构建 Release APK

```bash
cd android
./gradlew assembleRelease
```

Release APK 位于：`android/app/build/outputs/apk/release/app-release.apk`

## 插件列表

| 插件 | 用途 |
|------|------|
| `@capacitor/share` | 分享功能 |
| `@capacitor/toast` | 原生 Toast 提示 |
| `@capacitor/status-bar` | 状态栏控制 |
| `@capacitor/splash-screen` | 启动屏 |
| `@capacitor/preferences` | 本地存储 |
| `@capacitor/network` | 网络状态 |

## 注意事项

1. **HTTP 支持**：应用默认允许 HTTP 通信，用于连接后端服务器
2. **文件权限**：已配置媒体文件读取权限，支持图片上传
3. **WebSocket**：在移动设备上自动使用配置的后端地址

## 故障排除

### Gradle 构建失败

```bash
# 清理并重新构建
cd android
./gradlew clean
./gradlew assembleDebug
```

### 同步失败

```bash
# 强制重新同步
npx cap sync android --force
```

### 设备连接问题

确保设备已启用开发者选项和 USB 调试：
1. 设置 → 关于手机 → 连续点击版本号 7 次
2. 设置 → 开发者选项 → 启用 USB 调试
