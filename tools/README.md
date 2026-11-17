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

38 项检查包含实际触摸、中文输入、重名、截图编号、本地聊天发送、连续操作及停止条件，未确认发送不重发，以及面板停止、正常结束和错误清理。聊天与模型服务均在本地，不联系真实用户；不代表微信真机已经验证。
脚本临时清除模拟器代理，结束后恢复。测试页面禁止自动翻译，避免浏览器翻译条遮挡按钮。

## 真实 DeepSeek

`LiveDeepSeekTest` 在 `POCKET_AGENT_LIVE_KEY` 非空时运行，分别测试两种接口的看图与多轮工具回放。未设置时跳过；可选 `POCKET_AGENT_LIVE_PROXY=主机:端口`，只在测试期间生效。

`live-phone-smoke.mjs` 需要已安装 APK、完成浏览器首启的独立 1080×2400 模拟器，以及通过环境提供的 `POCKET_AGENT_LIVE_KEY`。脚本在设置界面保存加密密钥，调用真实模型，在本地聊天页选联系人、写中文草稿并发送一条测试消息。需要支付模型调用费用，不联系真实用户。

| 环境变量 | 用途 |
| --- | --- |
| `POCKET_AGENT_LIVE_KEY` | 临时用于真实接口测试，不写入报告 |
| `DSH_LIVE_OUT` | 结果目录，默认 `build/live-phone` |
| `DSH_PHONE_PROXY` | 模拟器代理，默认 `127.0.0.1:17897`；通过 adb 映射电脑的 7897 端口 |

脚本沿用 `ADB` 和 `DSH_TEST_SERIAL`，自动批准本地测试任务及发送确认卡片。通过或失败都会恢复配置、无障碍和代理，移除端口映射与临时 UI 转储。凭据输入阶段不截图，结果只含检查、页面事件、耗时和用量，不含密钥或请求头。
