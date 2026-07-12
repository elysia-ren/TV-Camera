# TV相机

适用于 **Android TV 9.0 (API 28) 及以上**智能电视、电视盒子、投影仪等大屏设备的相机应用。

## 核心特性

- **正确解码，告别花屏**  
  基于 Camera2 API，自动适配摄像头输出的 YUV_420_888 或 JPEG 格式，通过精确的 YUV→NV21 转换（正确处理 rowStride/pixelStride）确保预览和拍照画面完整清晰。

- **内置照片浏览器**  
  完全脱离系统图库，应用内提供缩略图列表与全屏查看功能，支持遥控器左右切换照片，直接读取 DCIM/TVCamera 目录或通过 MediaStore 访问（兼容 Android 10+ 分区存储）。

- **USB 摄像头稳定识别**  
  枚举所有可用摄像头（含外接 USB），自动选择最佳分辨率（优先 1080p/720p/480p），实时预览流畅。

- **纯遥控器操作**  
  所有交互（选择摄像头、拍照、查看照片、返回）均通过遥控器方向键、确认键和菜单键完成，焦点导航逻辑清晰。

## 技术栈

- **语言**：Java
- **最低 API**：28 (Android 9.0)  
- **目标 API**：30 (Android 11)  
- **相机框架**：Camera2 API  
- **预览**：TextureView（GPU 渲染，零拷贝）  
- **拍照**：ImageReader + 单次捕获（YUV 压缩或 JPEG 直出）  
- **图片存储**：Android 9 文件系统 / Android 10+ MediaStore  
- **图库**：RecyclerView 水平缩略图列表 + ViewFlipper 全屏浏览  
- **依赖**：AndroidX (appcompat, recyclerview, material)

## 项目结构

```
TVCamera/
├── build.gradle                 # 项目级构建脚本
├── settings.gradle              # 仓库与模块配置
├── gradle.properties
├── gradlew / gradle/            # Gradle Wrapper
├── build.sh                     # 一键编译脚本
└── app/
    ├── build.gradle             # 模块构建配置
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml  # 权限、Activity 声明
        ├── java/com/tvcamera/app/
        │   ├── MainActivity.java        # 摄像头选择主界面
        │   ├── CameraActivity.java      # 预览与拍照
        │   ├── GalleryActivity.java     # 内置图库
        │   ├── CameraHelper.java        # 摄像头枚举与分辨率查询
        │   └── YuvToNv21Converter.java  # YUV→NV21 转换工具
        ├── res/
        │   ├── layout/                  # 布局文件
        │   ├── values/                  # 颜色、字符串、主题
        │   └── drawable/                # 应用图标 (banner)
        └── ...
```

## 编译与安装

### 环境要求
- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Android SDK Platform 34 及 Build-Tools
- 已配置 `ANDROID_HOME` 和 `JAVA_HOME` 环境变量（或使用 Android Studio 内置 SDK）

### 编译
```bash
# 克隆/下载源码后，在项目根目录执行：
./gradlew assembleDebug
```
生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

也可直接使用 Android Studio 打开项目，点击 Run。

### 安装到设备
将 APK 通过 U 盘、adb 或网络传输到电视并安装：
```bash
adb install app-debug.apk
```
确保电视已开启“开发者选项”和“USB 调试”，或允许安装未知来源应用。

## 使用说明

1. 将 USB 摄像头插入电视的 USB 接口。
2. 打开“TV相机”应用，主界面会列出所有可用摄像头及其最佳分辨率。
3. 使用遥控器**上下键**选择摄像头，按**确认键**进入预览界面。
4. 预览界面：
   - 按**确认键**（或拍照键）拍照，按钮会短暂变为“拍照中…”。
   - 按**返回键**退出预览返回主界面。
   - 按**菜单键**打开内置图库。
5. 图库界面：
   - 左右移动焦点选择照片，底部显示文件名和分辨率。
   - 按**确认键**进入全屏查看。
   - 全屏下按**左右键**切换上一张/下一张，按**返回键**返回缩略图列表。
   - 按**菜单键**退出图库。

## 存储与权限

- 照片保存在 `DCIM/TVCamera` 目录下。
- Android 9 及以下：需要授予“存储空间”权限（首次拍照时会请求）。
- Android 10+：无需存储权限，照片通过 MediaStore 写入，图库也通过 ContentResolver 读取，符合分区存储规范。
- 摄像头权限会在进入预览时请求。

## 注意事项

- **摄像头热插拔**：若预览过程中拔掉摄像头，应用会在 3 秒后自动返回主界面；重新插入后主界面会刷新设备列表。
- **格式降级**：如果摄像头不支持 YUV_420_888，会自动切换为 JPEG 模式进行拍照（预览仍正常），但拍照速度会更快（免去压缩步骤）。
- **分辨率适配**：应用会优先选择 1080p，若不可用则依次尝试 720p、480p，最后使用最大可用分辨率。
- **设备兼容性**：理论上适用于所有标准 Android TV 9.0+ 设备。若特定电视对 Camera2 的支持不完整，可能导致无法打开摄像头，请确认 USB 摄像头是否被系统识别（可尝试使用其他相机应用检测）。

## 许可

本项目仅供个人学习与设备适配使用，不包含任何商业授权。使用本软件造成的数据丢失或设备问题请自行承担风险。

---

**祝您的大屏设备拍摄愉快！**
```
