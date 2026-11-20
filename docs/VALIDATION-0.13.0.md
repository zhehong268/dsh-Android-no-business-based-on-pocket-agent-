# 0.13.0 测试记录

2026-11-20。JDK 17、Android SDK 36、AOSP Android 16 模拟器。设备回归使用本地脚本模型。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:compileReleaseKotlin :app:lintDebug :phone-fixture:assembleDebug --rerun-tasks --offline --console=plain
```

1,100 项单测，0 失败，3 项外网测试跳过。Debug APK、Release Kotlin 和 Lint 通过，Lint 0 错误、111 条警告。Android 16 模拟器 101 项通过。

## 核对范围

- 运行控件原文与上报字族分别保留。按最新非输入文字节点生成字体参照，候选字体与当前控件字体线索分开显示。
- 系统桌面和锁屏壁纸主色使用 [WallpaperManager](https://developer.android.com/reference/android/app/WallpaperManager#getWallpaperColors(int))，锁屏壁纸原图不读取。应用背景使用当前画面采样，从背景样本中剔除有文字的控件、编辑框和系统键盘。
- 键盘区域来自 [AccessibilityWindowInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo#getRegionInScreen(android.graphics.Region))。区域没有键盘节点编号；截屏输出遮住键盘，坐标落在键盘内的点击会被拒绝。
- 外观核对只返回文字。选 `include_visible_colors=false` 时不截图。键盘收起时不主动展开。
- 模拟器读取了样本控件的 `monospace` 字族和原始文字，并生成节点参照图。软键盘展开后分别返回键盘和应用配色，点击键盘坐标被拒绝。通过截屏像素确认键盘区域被遮盖，文字报告未包含候选词。
- 持续变化页面上的五次纯文字读屏为 52～81 毫秒，每次均读到递增的页面计数。原有 50 毫秒本地等待预算不变，窗口读取和本机通信另计。

私有字体或皮肤资源、微信和 QQ 的聊天壁纸原文件均未读取。应用没有上报字族时，不能确认它实际使用的字体。厂商系统、自定义输入法和真机聊天应用待验证。

工作区普通自检前后均为 PASS 18 / WARN 3 / FAIL 11，未新增桌面环境失败项。中文扫描 186 个文件，乱码 0。
