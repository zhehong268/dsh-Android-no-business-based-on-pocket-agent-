# 设备回归

`device-smoke.mjs` 在独立模拟器里安装 APK，打开本地测试页，再通过 MCP 操作。
需要 Node.js 18+、adb，以及已经完成浏览器首启的模拟器。不要在日常使用的模拟器上运行。

```powershell
$env:ADB='D:\Android\Sdk\platform-tools\adb.exe'
$env:DSH_TEST_SERIAL='emulator-5556'
.\gradlew.bat :app:assembleDebug :phone-fixture:assembleDebug
$env:DSH_NATIVE_FIXTURE='1'
node tools/device-smoke.mjs
```

| 环境变量 | 用途 |
| --- | --- |
| `ADB` | adb 路径，默认使用 PATH |
| `DSH_TEST_SERIAL` | 独立模拟器编号，默认 `emulator-5556` |
| `DSH_TEST_APK` | APK 路径，默认使用 Debug 构建产物 |
| `DSH_TEST_OUT` | 结果目录，默认 `build/device-smoke` |
| `DSH_SMOKE_APPROVE` | 设为 `1` 时自动点击测试确认卡片 |
| `DSH_NATIVE_FIXTURE` | 设为 `1` 时增加原生聊天测试，需先构建 `:phone-fixture:assembleDebug` |
| `DSH_NATIVE_ONLY` | 设为 `1` 时跳过浏览器部分，仅运行已启用的原生测试 |
| `DSH_RESUME_SPEED` | 设为 `1` 时增加失败记录恢复、悬浮窗拖动、延迟发送与持续刷新耗时检查；需启用原生测试 |
| `DSH_CHAT_QA` | 设为 `1` 时增加纯文字聊天、追问、复制、停止、重试、进程恢复与清空检查；真实模型脚本也支持 |

默认需要手动批准手机上的控制申请。自动点击的坐标只适用于已核对的 1080×2400 AVD，其他尺寸不要启用。
脚本临时开启电脑连接，并将模型配置替换为不含凭据的本地夹具。原配置只保存在内存，结束后恢复；电脑连接关闭，临时配对码和端口映射清除。

浏览器部分检查实际触摸、中文输入、重名、截图编号、本地聊天发送、连续操作及停止条件，未确认发送不重发，以及面板停止、正常结束和错误清理。原生部分检查通讯录搜索、资料页、无标签和隐藏输入框、花体、语音切换、整段粘贴、深浅背景、大字体与不同分辨率。具体数量随浏览器是否上报勾选状态而异。
`phone-fixture` 是无网络权限的合成测试应用，记录粘贴、控件写入、键盘事件和发送次数；不读取真实联系人。测试通过不代表微信真机已经验证。
脚本临时清除模拟器代理，结束后恢复。测试页面禁止自动翻译，避免浏览器翻译条遮挡按钮。
两个脚本共用模拟器进程锁，不能在同一设备并发执行。修改测试配置前先停用自身无障碍服务，结束后恢复原无障碍与字体、分辨率和代理设置，避免配置被后台服务覆盖。
原进度只保存在内存，测试使用独立进度，结束后恢复；不会靠清除生产防重发保护让测试通过。

## 真实 DeepSeek

`LiveDeepSeekTest` 在 `POCKET_AGENT_LIVE_KEY` 非空时运行，分别测试两种接口的看图与多轮工具回放。未设置时跳过；可选 `POCKET_AGENT_LIVE_PROXY=主机:端口`，只在测试期间生效。

`live-phone-smoke.mjs` 需要已安装 APK、完成浏览器首启的独立 1080×2400 模拟器，以及通过环境提供的 `POCKET_AGENT_LIVE_KEY`。脚本在设置界面保存加密密钥，调用真实模型，在本地聊天页选联系人、写中文草稿并发送一条测试消息。需要支付模型调用费用，不联系真实用户。
设置 `DSH_NATIVE_FIXTURE=1` 时改用原生测试应用，从通讯录搜索姓名、核对资料并进入无标签输入框，检查实际粘贴和发送次数。

| 环境变量 | 用途 |
| --- | --- |
| `POCKET_AGENT_LIVE_KEY` | 临时用于真实接口测试，不写入报告 |
| `DSH_LIVE_OUT` | 结果目录，默认 `build/live-phone` |
| `DSH_PHONE_PROXY` | 模拟器代理，默认 `127.0.0.1:17897`；通过 adb 映射电脑的 7897 端口 |

脚本沿用 `ADB` 和 `DSH_TEST_SERIAL`，自动批准本地测试任务及发送确认卡片。通过或失败都会恢复配置、无障碍和代理，移除端口映射与临时 UI 转储。凭据输入阶段不截图，结果只含检查、页面事件、耗时和用量，不含密钥或请求头。
