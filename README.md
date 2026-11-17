# dsh Android

让模型在 Android 上读屏、点击和输入。支持登录 DeepSeek 账号，也可以自行配置模型服务。
手机可以独立运行任务；连接电脑后，也能通过 MCP 逐步操作。

基于 [PocketAgent 0.2.0](https://github.com/ManojDevarajulu/PocketAgent) 修改。
当前版本：0.3.0，包名 `dev.pocketagent`。

[下载 APK](dist/dsh-phone-agent.apk) · [安装说明](dist/README.md) · [电脑连接](docs/MCP-DIRECT.md) · [测试记录](docs/VALIDATION-2026-11-16.md)

## 安装

需要 Android 11 或更新版本。

1. 安装 APK，在设置里登录 DeepSeek 账号，或填写自己的模型配置。
2. 开启 dsh 无障碍服务。麦克风和通知权限按需开启。
3. 在应用内输入任务。也可以把“问 dsh”加到快捷设置磁贴，从其他应用发起任务。

读写文件前，需要在设置里授权一个文件夹。普通文件调用每次都会弹出确认。
任务中断后，首页会显示“继续任务”；恢复时会重新读取当前屏幕。

## 功能

- 读取文字、控件状态和截图；内容不清楚时可以放大局部或读取节点全文。
- 按文字、节点或截图编号操作。重名会返回候选，旧页面的编号不能继续使用。
- 输入、清空、追加文字，以及滚动、长按、双击和拖动。
- 搜索网页、读取网页正文，在已授权目录读写文本文件。
- 保存任务进度和已验证的操作路线。发送、删除、交卷等操作保留确认。

手机操作默认使用可看图的 `deepseek-flash`，难题可按需交给 `deepseek-v4-pro` 复核。
配置 Pro 时，操作部分仍会改用 Flash。其他服务商使用用户保存的配置。

## 构建

需要 JDK 17 和 Android SDK 36。工程路径请使用 ASCII 字符；当前 AGP 配置不接受中文路径。
设置好 `JAVA_HOME`、`ANDROID_HOME` 后，在工程目录运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。Linux 和 macOS 使用 `./gradlew`。
首次构建需要下载依赖，已有完整缓存时可以加 `--offline`。

0.3.0 的本地测试结果：938 项单测，0 失败、1 跳过；独立模拟器 15 项检查通过。
Debug 构建和 Release 分支编译通过，Lint 有 111 条警告、0 错误。[详细记录](docs/VALIDATION-2026-11-16.md)

## 已知问题

- 部分 WebView 不上报勾选状态，或接受点击后没有反应。此时会显示 `checked=unknown` 并补图，需要换定位方式或核对页面结果。
- 截图编号来自无障碍节点。纯画布上的按钮仍靠模型看图定位；没有单独集成离线 OCR，安全窗口可能禁止截图。
- 微信真机流程、真人账号登录、不同厂商系统和长任务还没有完整测过。重复任务基准使用脚本模型，不能据此计算真实模型的任务成功率。
- 进度保存的是摘要和动作记录。进程被直接杀掉时，最后一次写入可能丢失；恢复后要先查清发送、提交等操作是否已完成。
- 普通手机任务默认最多 40 步，答题任务最多 90 步，电脑直接控制最多 60 步。路线记忆最多保存 12 条，应用改版后可能失效。
- 文件工具只处理授权目录内的文本。搜索依赖 Bing RSS 和 DuckDuckGo HTML，服务变动可能导致失败。
- 电脑连接使用局域网 HTTP 和配对码，没有 TLS。只在可信网络使用，不要开放到公网。
- 仓库 APK 使用 debug 签名，并沿用旧版证书。自行构建的包可能无法覆盖安装；正式发布需要配置自己的签名。

## 许可

[AGPL-3.0](LICENSE)。上游说明保存在 [docs/UPSTREAM_README.md](docs/UPSTREAM_README.md)。
