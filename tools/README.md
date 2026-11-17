# 设备回归脚本

`device-smoke.mjs` 只允许模拟器；测试页完全本地，不登录账号、不发送消息。
需要预先完成浏览器首启，并启用可用的无障碍服务环境。

环境变量：ADB 指定 adb 路径；DSH_TEST_SERIAL 指定独立模拟器；DSH_TEST_APK 指定测试 APK；DSH_TEST_OUT 指定结果目录。
默认不自动确认，运行后按手机卡片批准。`DSH_SMOKE_APPROVE=1` 仅适用于本轮已核对的 1080×2400 测试 AVD；其他尺寸不要启用。
脚本通过 adb reverse 提供本地页面；临时开启的电脑连接和配对码在 finally 中清理。

```powershell
$env:ADB='D:\Android\Sdk\platform-tools\adb.exe'
$env:DSH_TEST_SERIAL='emulator-5556'
node tools/device-smoke.mjs
```
