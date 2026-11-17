# 开发约定

- 默认中文交流和提交说明。分支、标签、路径或协议字段有兼容问题时使用 ASCII。
- 修改前后跑本地工作区的 `env-selftest.ps1`。
- 安卓改动后跑完整单测（`--rerun-tasks`）、`assembleDebug` 和 `lintDebug`。
- 测试包括拒绝、重名、过期观察、未知状态和中断恢复。保留安全断言。
- 更新 README 的已知问题。设备测试和脚本模型测试分别记录。
- 不提交账号令牌、配对码、keystore、`local.properties`、构建缓存和私人截图。
- 工程路径使用 ASCII。明确按 UTF-8 读写中文文件，不用 PS 5.1 的 `Get-Content -Raw | Set-Content` 修改源码。
- 不运行关闭或重启用户桌面壳、宿主的脚本来做测试。
