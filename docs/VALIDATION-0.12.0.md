# 0.12.0 测试记录

2026-11-19。JDK 17、Android SDK 36、AOSP Android 16 模拟器。设备回归使用本地脚本模型。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:compileReleaseKotlin :app:lintDebug :phone-fixture:assembleDebug --rerun-tasks --offline --console=plain
```

1,098 项单测，0 失败，3 项外网测试跳过。Debug APK、Release Kotlin 和 Lint 通过，Lint 0 错误、111 条警告。Android 16 模拟器 94 项检查通过。

- 持续刷新页面的稳定等待与空树重试共用 50 毫秒上限。静止页面连续 25 毫秒无事件后读取。答题滚动后的短暂停顿用于核对重叠区域和题号。
- 连续五次纯文字读屏为 32～88 毫秒，中位 56 毫秒。扩展控件树加截图为 344～360 毫秒，中位 347 毫秒。两组返回的页面计数均递增。
- 答题单测走过旧的 90 次决策上限，验证后续仍能完成，并核对滚动使用小步参数。
- 模拟器中失败后拒绝新任务，续跑回到原应用并读到原进度。悬浮窗可在原任务补充问题并显示回复。一次答题回归等待模型超过 40 秒后仍完成。
- QQ 构造控件测试覆盖本人气泡、旧气泡、他人气泡及发送中状态。发送核验仍要求同一会话、新增匹配正文和已清空的输入框。
- Android 16 切到后台再返回后，无障碍授权和服务连接均保留。Android 管理服务连接和授权，不同厂商真机仍待验证。[Android 无障碍服务生命周期](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#lifecycle)

QQ、TIM 和微信的实际账号未作为本次设备回归对象。无法读取气泡正文时保留未核验状态。

工作区普通自检前后均为 PASS 18 / WARN 3 / FAIL 11。中文扫描 185 个文件，乱码 0。
