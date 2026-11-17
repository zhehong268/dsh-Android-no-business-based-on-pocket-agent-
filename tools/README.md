# 设备回归

`device-smoke.mjs` 在独立模拟器里安装 APK，打开本地测试页，再通过 MCP 操作。
需要 Node.js 18+、adb，以及已经完成浏览器首启的模拟器。不要在日常使用的模拟器上运行。

```powershell
$env:ADB='D:AndroidSdkplatform-toolsadb.exe'
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
脚本会临时开启电脑连接，通过 adb reverse 提供测试页，结束后关闭连接并清理配对码和端口映射。
