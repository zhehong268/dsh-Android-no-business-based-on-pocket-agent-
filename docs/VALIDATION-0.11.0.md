# 0.11.0 测试记录

2026-11-18。JDK 17、Android SDK 36，独立 Android 16 / API 36 模拟器。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:compileReleaseKotlin :app:lintDebug :phone-fixture:assembleDebug --rerun-tasks --offline --console=plain
```

1,095 项单测，0 失败，3 项外网测试跳过。Debug APK、Release Kotlin 编译、原生测试应用和 Lint 通过。Lint 0 错误、111 条警告。色彩单测检查浅色与深色下的结果正文、辅助文字和主按钮的对比度。

## 模拟器

89 项设备检查通过。

- 任务成功、模型错误和手动停止后移除运行面板，并显示结果弹窗。成功弹窗超过 15 秒仍可见，点“知道了”关闭。关闭失败弹窗不重试。
- 在成功弹窗打开时从系统浅色切到深色，截图核对同一段结果仍可见，背景和按钮同步换色。深色失败弹窗显示错误原因及“关闭”“继续任务”。
- 在失败弹窗点“继续任务”，复用原任务目标和已保存的步骤，回到原应用。任务完成后显示新的结果弹窗。
- 联系人精确匹配、重名拦截、草稿粘贴、发送确认和中断恢复纳入设备回归。聊天答疑仍在页面内显示回答。

本次使用本地脚本模型；真实 DeepSeek 的上次联调见 [0.10.0 测试记录](VALIDATION-0.10.0.md)。微信真机与厂商系统待验证。

工作区普通自检前后均为 PASS 18 / WARN 3 / FAIL 11，未新增桌面环境失败项。
