# Live图生成器（安卓 APP）

把**图片 / 视频**转成 **Live 图（Google Motion Photo）** 的安卓应用，
逻辑完全对标你电脑上的 `video_转Live图.py`：

- **视频** → 取首帧作为封面，整段（或按设定的分段时长切成多段）不解码流拷贝为 MP4，封装成 Live 图
- **图片** → 生成 3 秒「手持漂移感」动态视频（整幅画面轻微平移 + 微旋转，**不做变焦放大**），再封装成 Live 图
- 输出文件保存到：**你选择的目录 / Live图** 文件夹下，文件名与原文件同名、扩展名 `.jpg`
- 封装格式与电脑版一致：封面 JPEG + XMP 元数据（Google Motion Photo）+ 末尾追加 MP4
- 在 **小米相册 / Google 相册** 里会显示为可动的 Live 图

> 适配：小米 15 / 安卓 16（API 36 也能跑，targetSdk = 35）。
> 已针对 Android 11+ 作用域存储做了处理，通过系统文件选择器读取源文件、通过 SAF（Storage Access Framework）写入你选择的目录，**无需任何存储权限弹窗**。

---

## 一、怎么拿到可安装的 APK（推荐：云端一键编译，本机不用装任何东西）

> 因为本工程是原生安卓（Kotlin）项目，需要 Android 编译环境。
> 我没法在这台电脑上直接给你编出 APK（环境里没有 Android SDK / 没法联网下载），
> 所以准备了下面的 GitHub Actions 工作流，你只要在 GitHub 上点一下就能拿到 APK。

1. 注册 / 登录一个 GitHub 账号（https://github.com）。
2. 新建一个**空仓库**（比如叫 `LivePhotoMaker`）。
3. 把本工程 `LivePhotoApp/` 里的**所有文件**上传到这个仓库（可直接拖拽，或用 GitHub Desktop）。
4. 进入仓库的 **Actions** 标签 → 看到 `Build LivePhotoMaker APK` → 点 **Run workflow**（手动触发）。
   - 也可以直接 `git push` 到 `main`/`master` 分支，会自动触发编译。
5. 等待 3~6 分钟，编译完成后在 **Artifacts** 里下载 `LivePhotoMaker-debug-apk`（里面是 `app-debug.apk`）。

### 备选：用 Android Studio 在本机编译
1. 安装 Android Studio（https://developer.android.com/studio）。
2. 打开本工程目录 `LivePhotoApp/`（`File → Open`）。
3. 等待 Gradle 同步完成（会自动下载 SDK 35 等）。
4. 菜单 `Build → Build Bundle(s) / APK(s) → Build APK(s)`。
5. 编译产物在 `app/build/outputs/apk/debug/app-debug.apk`。

---

## 二、怎么安装到小米 15

1. 把 `app-debug.apk` 传到手机（微信文件传输 / 数据线 / 网盘都行），用**小米自带的「文件管理」**找到它。
2. 点击 APK → 若提示「允许安装未知应用」，按提示给「文件管理」开启**安装未知应用**权限。
3. 安装完成，桌面会出现 **「Live图生成器」** 图标。

> 说明：这是 debug 签名的 APK，仅供自用，小米不会报毒，可放心安装。
> 若要上架或长期分发，可自行用 `Build → Generate Signed Bundle / APK` 做正式签名。

---

## 三、怎么用

1. 打开「Live图生成器」。
2. 点 **① 选择保存目录**（用系统文件选择器选一个目录，比如 `Download`、`DCIM`、`Documents` 等）。
3. 在 **② 转换模式** 下拉框里选择一种模式：
   - **图片或者视频转换**：直接把选中的图片或视频各转成 1 个 Live 图（视频整段转换，不切割）。
   - **长视频切割**：选这个才会出现「分段时长（秒）」输入框；填每段秒数（默认 3，可填 1~30），长视频会被切成多段，每段生成一个独立 Live 图。
4. 点 **选择图片 / 视频**（可一次多选，挑你要转的文件）。
5. 点 **③ 开始转换**。
6. 进度条走完，日志里会显示每个文件已保存到 `你选的目录/Live图/xxx.jpg`。
   - 长视频切割模式下，文件名形如 `xxx_1.jpg`、`xxx_2.jpg`……每段都是一个独立 Live 图。
7. 打开小米相册 / Google 相册，进入你选目录下的 `Live图` 文件夹，长按照片即可看到动态效果。

---

## 四、注意事项 / 排错

- **视频走流拷贝**（不解码、不重编码、画质无损），支持 H.264 / HEVC(H.265) 等常见编码；自动从关键帧开始截取，避免开头花屏。
- **两种模式**：
  - 「图片或者视频转换」：每个选中的文件直接生成 1 个 Live 图（视频按整段转换，不切割）。
  - 「长视频切割」：按你设置的「分段时长（秒）」把视频切成多段，每段各生成 1 个 Live 图（文件名加 `_1`/`_2`…后缀）；视频时长不足一段时只生成 1 张。
- **图片生成约 3 秒「手持漂移感」动效**（两种模式都适用，不受分段设置影响）：
  - 不再使用"以画面中心等比放大"的变焦效果（那种效果所有像素沿半径扩散，一眼就看出是数字特效）；
  - 改为模拟手持拍摄：整幅画面轻微平移（横向≈1.5%、纵向≈1.2% 画幅）+ 极小角度旋转（<0.2°）；
  - 运动曲线由两组不同频率的正弦叠加而成，有机、非匀速，避免机械的"推拉"感；
  - 首帧与末帧回到原始取景，因此**封面与你的原图完全一致**，播放时也没有跳变；
  - 裁切量按每帧位移动态计算（最小安定裁切），既不会露出黑边，也把画幅损失压到最低。
- 个别国产相册可能不识别 Motion Photo，请用**小米相册**或 **Google 相册**查看动态效果；
  在文件管理器里它显示为一个普通的 `.jpg`，这是正常的。
- 保存目录由你自己选择，应用会在该目录下自动创建 `Live图` 子文件夹；如果因系统限制无法创建，文件会直接落在所选目录下。

---

## 五、工程结构

```
LivePhotoApp/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/livephotomaker/
│       │   ├── MainActivity.kt        # 界面 + 选择/转换/保存流程
│       │   ├── MediaEngine.kt         # 视频截取 / 图片手持漂移视频 / 封面提取
│       │   └── MotionPhotoWriter.kt   # JPEG+XMP+MP4 封装（对标原 Python）
│       ├── res/                       # 布局、主题、图标
│       └── proguard-rules.pro
├── build.gradle / settings.gradle / gradle.properties
└── .github/workflows/build.yml       # 云端一键编译 APK
```
