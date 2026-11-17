# 安装 dsh

下载本目录的 [dsh-phone-agent.apk](dsh-phone-agent.apk)，在 Android 11 或更新版本上安装。
0.3.0 沿用旧版 debug 证书，可以覆盖升级。自行构建时需要使用同一证书才能覆盖。

1. 在设置里登录 DeepSeek 账号，或填写自己的模型配置。
2. 开启 dsh 无障碍服务；麦克风、通知权限按需开启。
3. 读写文件时，先在设置里授权一个文件夹。
4. 从应用内发任务，或添加快捷设置磁贴“问 dsh”。

任务中断后可在首页继续。发送、删除和交卷等操作仍需确认。
电脑连接默认关闭，使用方法见仓库中的 `docs/MCP-DIRECT.md`。

## 版本信息

- 版本：0.3.0 / versionCode 3
- 包名：`dev.pocketagent`
- 大小：8,404,122 字节
- SHA-256：`b7bca33759e9f815877fb7263ddbd23526ea4aa9b4ecf65fc7e7232adb173d99`

## 已知问题

WebView 可能不上报状态，或点击后没有反应，需要结合截图核对并换一种操作方式。
微信真机、真人登录和不同厂商系统尚未完整测试。没有离线 OCR，安全窗口可能禁止截图。
恢复任务后，应先确认上一次发送、提交是否已经完成。

完整说明和测试记录见[项目仓库](https://github.com/zhehong268/dsh-Android-no-business-based-on-pocket-agent-)。
