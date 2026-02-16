# OOC Android 应用

使用 Capacitor 将 OOC 前端打包为 Android 应用。

## 获取 APK（推荐）

### GitHub Actions 自动构建

项目已配置 GitHub Actions 自动构建 Android APK，**支持覆盖安装**（无需卸载旧版本）。

**下载方式：**

1. **最新构建（推荐）**
   - 访问 GitHub 仓库 → Actions 标签页
   - 点击最新的 "Build Android APK" 工作流运行记录
   - 在 Artifacts 区域下载 `ooc-android-v1.0.xxx.apk`

2. **Release 版本**
   - 访问 GitHub 仓库 → Releases 页面
   - 下载对应版本的 APK

3. **手动触发构建**
   - 访问 Actions → Build Android APK → Run workflow
   - 可指定自定义版本号

**覆盖安装说明：**
- 每次构建的 `versionCode` 自动递增
- 新 APK 可以直接覆盖安装旧版本，无需卸载
- 数据（如登录状态、设置）会保留

## 本地构建

如需本地构建，请参考以下方法：

### 前置要求

1. **Node.js** 18+ 和 pnpm
2. **Android SDK** (用于构建 APK)
   - 如果没有 Android Studio，可以只安装命令行工具
   - 设置环境变量 `ANDROID_HOME` 或在 `android/local.properties` 中指定 SDK 路径

### 方法一：一键构建脚本（最简单）

```bash
# 进入前端目录
cd frontend

# 运行构建脚本（自动完成所有步骤）
./scripts/build-android.sh
```

脚本会自动执行：
1. 安装依赖（如果未安装）
2. 构建前端（`pnpm build`）
3. 同步到 Android 项目（`npx cap sync android`）
4. 构建 APK（`./gradlew assembleDebug`）

构建完成后输出 APK 路径和文件大小。

### 方法二：使用 package.json 脚本

```bash
# 1. 构建前端 + 同步到 Android + 构建 APK
pnpm mobile:build

# 2. 或者分步执行：
pnpm build           # 构建前端
pnpm android:sync    # 同步到 Android 项目
pnpm android:build   # 构建 APK
```

### 方法三：手动执行每一步

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

### 方法四：使用 Android Studio（调试用）

```bash
# 同步后打开 Android Studio
pnpm android:open
# 或
npx cap open android
```

然后在 Android Studio 中：
- 点击 "Build" → "Build Bundle(s) / APK(s)" → "Build APK(s)"
- 或使用设备运行/调试

## 构建脚本参考

| 脚本 | 命令 | 说明 |
|------|------|------|
| **一键构建** | `./scripts/build-android.sh` | 完整的构建流程脚本 |
| **移动端构建** | `pnpm mobile:build` | package.json 定义的完整构建 |
| **同步** | `pnpm android:sync` | 同步前端代码到 Android 项目 |
| **构建 APK** | `pnpm android:build` | 构建 Android APK |
| **打开项目** | `pnpm android:open` | 在 Android Studio 中打开 |
| **运行** | `pnpm android:run` | 在连接的设备上运行应用 |
| **Capacitor 同步** | `npx cap sync android` | 底层同步命令 |
| **Capacitor 打开** | `npx cap open android` | 底层打开命令 |

## 输出位置

构建成功后，APK 文件位于：

```
frontend/android/app/build/outputs/apk/debug/app-debug.apk
```

Release 版本：
```
frontend/android/app/build/outputs/apk/release/app-release.apk
```

## 项目结构

```
frontend/
├── android/              # Android 原生项目
│   ├── app/src/main/     # 应用源码
│   │   ├── AndroidManifest.xml
│   │   ├── assets/public/  # 前端构建产物（自动生成，不提交）
│   │   └── res/          # 资源文件
│   ├── build.gradle      # 应用构建配置
│   └── local.properties  # SDK 路径（本地文件，不提交）
├── capacitor.config.ts   # Capacitor 配置
├── scripts/
│   └── build-android.sh  # 一键构建脚本
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
cd android
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
4. **首次构建**：第一次构建会下载 Gradle，可能需要几分钟

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

### SDK 未找到

```bash
# 创建 local.properties 指定 SDK 路径
echo "sdk.dir=/path/to/android-sdk" > android/local.properties
```

### 设备连接问题

确保设备已启用开发者选项和 USB 调试：
1. 设置 → 关于手机 → 连续点击版本号 7 次
2. 设置 → 开发者选项 → 启用 USB 调试
