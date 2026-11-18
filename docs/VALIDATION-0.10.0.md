# 0.10.0 测试记录

2026-11-18。JDK 17、Android SDK 36，独立 AOSP Android 16 / API 36 模拟器。

## 本地构建

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:compileReleaseKotlin :app:lintDebug :phone-fixture:assembleDebug --rerun-tasks --offline --console=plain
```

1,095 项单测，0 失败，3 项外网测试跳过。Debug APK、Release Kotlin、原生测试应用和 Lint 通过，Lint 0 错误、112 条警告。新增断言分别覆盖共享读屏时间预算、一次模型调用完成查人与草稿、手势中断后的实际发送证据。

## 核对范围

- 100 毫秒用于页面稳定等待与空控件树重试。页面连续 40 毫秒无事件时可提前读取。控件复制、截图、传输和模型请求分别计时。
- `read_screen(include_image=false)` 直接返回文字与控件状态，更新节点编号。默认读屏仍附截图。
- Android 13 及更新版本读取前清除选中窗口的控件子树缓存，避免快速读屏取到旧子节点。[Android 无障碍服务 API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#clearCachedSubtree(android.view.accessibility.AccessibilityNodeInfo))
- `fill_chat(open_if_needed=true)` 在当前聊天应用内查人、核对会话标题并填草稿。结果延迟时复查控件，重名时停在候选页。发送仍单独确认。
- 手势派发中断时，如果同一会话出现匹配的新正文且输入框已清空，可以确认已发送。结果不明时继续保留防重发保护。

## Android 16 模拟器

原生聊天、任务续跑和聊天答疑共 79 项设备检查通过。联系人搜索模拟 320 毫秒延迟；查人和填草稿验证了精确匹配、一次粘贴和无发送，重名时停在候选页。发送、悬浮窗拖动与停止、失败后继续原任务、多轮聊天保留上下文也纳入回归。

## 读屏耗时

测试页面每 20 毫秒更新一次计数。两组请求各执行 5 次，每次返回的页面计数均递增。纯文字请求的采样间隔为 30 毫秒，未计入耗时。

| 请求 | 耗时范围 | 中位 |
| --- | ---: | ---: |
| `read_screen(include_image=false)` | 53～93 毫秒 | 88 毫秒 |
| `read_ui_source` 与截图 | 344～357 毫秒 | 351 毫秒 |

耗时包含模拟器和本机 MCP 往返。前者只读取控件，后者还包含扩展控件原文、截图与编码。读屏上限由虚拟时钟单测验证。

## DeepSeek 联调

使用 `deepseek-flash` 和 API 密钥在 Android 16 原生测试应用中完成 9 项检查。模型通过看图和工具测试，找到指定联系人，识别无标签正文框并粘贴一次。普通导航与输入无需逐步确认，发送确认一次，核对一条新增消息与输入框清空。聊天答疑及追问分别返回 42、40，未开启手机任务悬浮窗。

本次模型自己选择的路径，查人和填草稿为 6 轮模型调用、6 次设备动作，应用计时 18.830 秒；发送为 1 轮、1 次动作，4.165 秒。单测另覆盖模型选择合并工具时一轮完成草稿。

微信 8.0.79 真机、不同厂商 Android 16 和 DeepSeek 账号登录令牌仍待验证。

## 工作区

修改前普通自检为 PASS 18 / WARN 3 / FAIL 11，改后完整自检为 PASS 19 / WARN 3 / FAIL 12。余额插件 97 项通过。桌面安装和依赖缺失项详见 `screen100-after.log`。中文扫描 183 个文件，乱码 0，英文待判断项 59。
