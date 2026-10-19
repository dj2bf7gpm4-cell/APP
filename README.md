# Android Shell MCP（原生 App 版）

一个原生安卓 App：前台服务里跑一个 MCP 服务器（Streamable HTTP），用 `su` 调用本机
shell。纯 Java、零第三方依赖（不用 AndroidX），性能远好于 Termux + Python 版，装一个
APK 就能用，不依赖电脑、不依赖 Termux。

- 工具 `shell(command, root=false, timeout=30)` —— 执行命令，`root=true` 走 `su`
- 工具 `device_info()` —— 型号 / 安卓版本 / root 自检
- 界面：填端口 → 点“启动服务”，前台常驻；默认只绑 `127.0.0.1`
- root 命令自动写死完整 PATH（系统目录在前 + Termux 目录兜底），不再有“root 下找不到命令”

---

## 怎么拿到 APK

本工程不含编译好的 APK（需要 Android SDK 编译）。两种方式产出 APK，**推荐方式 A，全程不用装 Android Studio**。

### 方式 A：GitHub 云端自动编译（推荐）

1. 在 GitHub 新建一个仓库，把本工程所有文件上传上去（保留目录结构，`.github/` 一起传）。
2. 进仓库的 **Actions** 页，会看到 “Build APK” 工作流；首次可能需要点一下 **Enable**。
3. 点 **Run workflow**（或直接 push 触发），等 2–3 分钟变绿。
4. 进这次运行，底部 **Artifacts** 里下载 `ShellMcp-debug-apk`，解压得到 `app-debug.apk`。
5. 把 APK 传到手机，安装（允许“未知来源”）。

> 产出的是 debug 签名 APK，可直接安装。想要 release 包就把 workflow 里的
> `assembleDebug` 改成 `assembleRelease`（已配置用 debug 签名，同样可装）。

### 方式 B：本地用 Android Studio / 命令行

- Android Studio：打开本工程目录，等它同步完，`Build > Build APK(s)`。
- 命令行（已装 Android SDK，设好 `ANDROID_HOME`）：
  ```
  ./gradlew assembleDebug
  ```
  产物在 `app/build/outputs/apk/debug/app-debug.apk`。

---

## 安装后怎么用

1. 打开 App，端口默认 `8000`，点 **启动服务**。通知栏出现“Shell MCP 正在运行”。
2. 在手机的 MCP 客户端 App 里添加服务：
   - Transport / 类型：**Streamable HTTP**（有的叫 HTTP / Remote）
   - URL：`http://127.0.0.1:8000/mcp`
   - 无需 API Key / Header
3. 第一次跑 `root=true` 的命令时，Magisk/KernelSU 会弹授权框 —— 选**允许**并勾“记住”。

勾选“允许局域网访问”会绑到 `0.0.0.0`，同一 Wi-Fi 下别的设备也能连（等于把 root shell
开放给局域网，谨慎，别在公共网络用）。默认只绑 `127.0.0.1`，仅本机 App 能连，最安全。

---

## 工程结构

```
ShellMcp/
├─ app/
│  ├─ build.gradle
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ java/dev/shellmcp/
│     │  ├─ MainActivity.java   界面（启停、端口、状态）
│     │  ├─ McpService.java     前台服务
│     │  ├─ McpServer.java      MCP over HTTP（纯 ServerSocket）
│     │  ├─ Tools.java          工具定义/调用
│     │  └─ Shell.java          su/sh 执行 + root PATH 修复
│     └─ res/...
├─ .github/workflows/build.yml  云端自动编译
├─ build.gradle / settings.gradle / gradle.properties
└─ gradlew + gradle/wrapper/    Gradle Wrapper
```

## 说明

- minSdk 26，targetSdk 34；前台服务类型 `dataSync`。
- 非 root 命令用 `/system/bin/sh`；root 命令用 `su -c`，并在命令前注入完整 PATH。
- 命令输出上限 2MB，超出截断，防止被 `logcat`/`cat` 大文件刷爆。
