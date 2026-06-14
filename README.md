# 电视家极速版 (Dianshijia Lite)

一个专为老旧安卓电视/机顶盒设计的电视直播软件，完美适配 **Android 4.1 (API 16)** 及以上系统。支持读取并解析 HLS/M3U8 直播源，提供极速开播与极简遥控交互体验。

---

## 📺 应用特性

1. **兼容超低系统**：深度优化，支持在早至 Android 4.1 的电视盒子与投影仪上顺利安装与播放。
2. **极速开播与续播**：首次下载后自动离线缓存直播列表。再次启动时，即使在无网或慢网环境下，也会根据最后一次播放的频道信息进行极速续播（秒开）。
3. **完全电视遥控器交互**：
   - **方向键上/下**：快速切台。
   - **OK/确认键**：呼出双列侧边栏频道菜单。
   - **方向键左/右**：在菜单中快速于“分类列表”与“频道列表”之间转移焦点。
   - **数字键 (0-9)**：直接遥控输入台号（如 `001`, `006`, `15` 等），停止输入 2 秒后自动换台。
   - **返回键**：菜单展示时关闭菜单，播放状态下连续按两次返回键退出，防止误触。
4. **开机自启动**：设备开机后自动拉起本 App，开机即看，体验与真实电视一致。
5. **支持 HTTP 明文传输**：在较高版本安卓系统中亦能顺利播放非加密的 HTTP 直播流。

---

## 🛠️ 如何在 GitHub 上进行云端自动编译

为了方便您获取最终的 `app-debug.apk` 安装包，本项目已经预置了 **GitHub Actions** CI/CD 自动编译工作流。**您无需在本地电脑上安装任何 JDK、Android SDK 以及 Gradle 编译环境。**

### 编译步骤：

1. **新建 GitHub 仓库**：
   在您的 GitHub 账号下新建一个空的私有或公开仓库（例如 `dianshijia-lite`）。
   
2. **推送代码至 GitHub**：
   在项目根目录下，打开终端（PowerShell 或 Git Bash），运行以下命令将本地代码提交并推送到您的 GitHub 仓库中：
   ```bash
   # 添加所有代码文件
   git add .
   
   # 提交
   git commit -m "Initialize Dianshijia Lite TV App with GitHub Actions support"
   
   # 关联您的 GitHub 远程仓库 (请将下面的 URL 替换为您自己仓库的实际地址)
   git remote add origin https://github.com/您的用户名/您的仓库名.git
   
   # 推送到 master 分支
   git push -u origin master
   ```

3. **等待 GitHub Actions 自动编译**：
   - 打开您的 GitHub 仓库网页，点击顶部的 **Actions** 标签卡。
   - 您会看到一个正在运行的工作流任务，名称为 **Build Android APK**。
   - 整个编译过程（拉取代码 $\rightarrow$ 配置 JDK 11 $\rightarrow$ 自动拉取 Gradle 7.4 $\rightarrow$ 编译并打包 APK）大约需要 2-3 分钟。

4. **下载生成的 APK 安装包**：
   - 待 Actions 运行结果变成 **绿色勾（Success）** 后，点击进入该次运行记录详情。
   - 滚动页面到最下方的 **Artifacts (产物)** 区域。
   - 点击 **DianshijiaLite-Debug-APK** 进行下载。
   - 下载后会解压出一个 `app-debug.apk` 文件，这便是可以直接安装在电视上的安装包！

---

## 🛠️ 本地代码目录结构

```text
├── .github/workflows/
│   └── android.yml            # GitHub Actions 自动构建脚本 (核心编译入口)
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/dianshijia/lite/
│   │   │   │   ├── model/Channel.java       # 频道数据模型
│   │   │   │   ├── parser/M3uParser.java    # M3U 网络请求与字符串解析
│   │   │   │   ├── receiver/BootReceiver.java # 开机自启动广播接收器
│   │   │   │   └── MainActivity.java        # 视频播放器与遥控器交互核心
│   │   │   ├── res/
│   │   │   │   ├── drawable/
│   │   │   │   │   └── selector_item.xml    # 遥控焦点高亮状态选择器
│   │   │   │   ├── layout/
│   │   │   │   │   ├── activity_main.xml    # 全屏及侧边栏主布局
│   │   │   │   │   ├── item_category.xml    # 分类列表项
│   │   │   │   │   └── item_channel.xml     # 频道列表项
│   │   │   │   ├── mipmap-xxhdpi/
│   │   │   │   │   └── ic_launcher.png      # 桌面图标
│   │   │   │   └── values/
│   │   │   │       ├── colors.xml           # TV暗黑磨砂玻璃与高亮焦点配色
│   │   │   │       ├── strings.xml          # 静态文本
│   │   │   │       └── styles.xml           # 全屏黑底无状态栏电视专属主题
│   │   │   └── AndroidManifest.xml          # 应用配置文件 (权限与启动入口)
│   │   └── build.gradle                     # app模块依赖配置文件 (API 16适配)
│   └── build.gradle                         # 根项目构建脚本
├── settings.gradle                          # 项目包含模块申明
└── .gitignore                               # Git 排除规则
```

---

## ⚠️ 兼容性及网络说明

- **直播源地址**：`https://my.3223516.xyz/tv.m3u`。App 启动后会通过 OkHttp 异步连接此地址进行下载。
- **明文传输**：由于该直播源采用 `http://` 明文协议传输，我们在 `AndroidManifest.xml` 中配置了 `usesCleartextTraffic="true"`。如果您将软件部署在 Android 9.0 或更高的电视盒子上，依然能够正常解码并播放，不会受系统强制 HTTPS 安全策略的限制。
- **首播缓存**：本地首播缓存将存储在应用的 `getCacheDir()` 下，即便离线或无法获取最新数据，原先解析成功的频道也绝不会丢失。
- **开机自启**：在 Android 4.1 系统的老盒子上开机自启动一般能 100% 成功。而在部分较新的安卓系统上，开机自启动可能会受到系统的安全软件限制，届时需要手动在电视设置中授予该软件“允许后台自启”权限。


