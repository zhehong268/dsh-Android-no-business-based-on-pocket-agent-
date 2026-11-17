# 设备回归

`device-smoke.mjs` 在独立模拟器里安装 APK，打开本地测试页，再通过 MCP 操作。
需要 Node.js 18+、adb，以及已经完成浏览器首启的模拟器。不要在日常使用的模拟器上运行。

```powershell
$env:ADB='D:\Android\Sdk\platform-tools\adb.exe'
$env:DSH_TEST_SERIAL='emulator-5556'
node tools/device-smoke.mjs
```

| 环境变量 | 用途 |
| --- | --- |
| `ADB` | adb 路径，默认使用 PATH |
| `DSH_TEST_SERIAL` | 独立模拟器编号，默认 `emulator-5556` |
| `DSH_TEST_APK` | APK 路径，默认使用 Debug 构建产物 |
| `DSH_TEST_OUT` | 结果目录，默认 `build/device-smoke` |
| `DSH_SMOKE_APPROVE` | 设为 `1` 时自动点击测试确认卡片 |

默认需要手动批准手机上的控制申请。自动点击的坐标只适用于已核对的 1080×2400 AVD，其他尺寸不要启用。
脚本临时开启电脑连接，并将模型配置替换为不含凭据的本地夹具。原配置只保存在内存，结束后恢复；电脑连接关闭，临时配对码和端口映射清除。

35 项检查包含实际触摸、中文输入、重名、截图编号、本地聊天发送，未确认发送不重发，以及面板停止、正常结束和错误清理。聊天与模型服务均在本地，不联系真实用户；不代表微信真机已经验证。
