# DSH 融合版 1.2.0-fusion.1

融合版使用独立 Android 包名 `io.github.zhehong268.dsh_fusion`，桌面图标沿用原版灰鲸。版本代码为 `11230001`。维护者为 [zhehong268](https://github.com/zhehong268)，融合代码按 [GNU AGPL-3.0](../LICENSE) 提供。

## 本次借鉴的开源项目

| 项目 | 许可 | 本次使用 |
| --- | --- | --- |
| [DSH Go 1.2.0](https://github.com/ccch1mneyyy/dsh-Go/releases/tag/v1.2.0)，ccch1mneyyy 和贡献者 | AGPL-3.0 | Flutter 页面、DeepSeek 账号授权、本机运行时集成、会话和手机操控入口 |
| [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) | MIT | 随 DSH Go 使用的模型和任务引擎 |
| [Phase](https://github.com/CikeSeven/phase)，CikeSeven | MIT | DSH Go 上游已有的无障碍读屏、操作和悬浮控件实现 |
| [PocketAgent 0.2.0](https://github.com/ManojDevarajulu/PocketAgent)，ManojDevarajulu 和贡献者 | AGPL-3.0 | 原版 dsh Android 的上游 |
| [dsh Android](../NOTICE.md)，zhehong268 和贡献者 | AGPL-3.0 | 微信小步滚动，微信、QQ 和 TIM 发送回读，壁纸、字族及键盘配色采样 |
| [SQLite](https://sqlite.org/copyright.html) | 公有领域 | 使用 DSH Go 1.2.0 ARM64 APK 中的 SQLite 原生库 |

PRoot、talloc、WebRTC、字体等随附依赖的完整署名见 [DSH Go 第三方声明](https://github.com/ccch1mneyyy/dsh-Go/blob/c1a84e3f833ad59ff07a5027994dae3935907a2d/THIRD_PARTY_NOTICES.md)。应用内开源许可页也收录了融合项目的署名。

## 变更与验证

- 微信按约四分之一屏纵向滚动。微信、QQ 和 TIM 按控件发消息后，回读会话标题、新增本人气泡和草稿状态。证据不足时提示核对。
- 无障碍页分别显示系统授权和服务连接，增加受限制设置的开启教程。事件合并间隔是 50 毫秒。
- 文件从系统选择器添加。Android 安装包没有共享存储权限，本机引擎工作区在应用私有目录。
- 手机控制和任务结束回归共 57 项通过，消息核验 JVM 测试通过。隔离模拟器安装后划掉最近任务再打开，保留无障碍授权和连接。

## 构建

融合源码由 [DSH Go 固定基线](https://github.com/ccch1mneyyy/dsh-Go/tree/c1a84e3f833ad59ff07a5027994dae3935907a2d) 与 [融合补丁](dsh-go-1.2.0-fusion.patch) 组成。使用 Flutter 3.47.7、JDK 17、Android SDK 36 和 Python 3.11 或以上版本。将本仓库检出到名为 `dsh-android` 的目录，再在其上级目录运行以下命令。

```sh
git clone --branch v1.2.0 --depth 1 https://github.com/ccch1mneyyy/dsh-Go.git dsh-go
cd dsh-go
git rev-parse HEAD
git apply --check ../dsh-android/fusion/dsh-go-1.2.0-fusion.patch
git apply ../dsh-android/fusion/dsh-go-1.2.0-fusion.patch
```

确认提交为 `c1a84e3f833ad59ff07a5027994dae3935907a2d`。从 [DSH Go v1.2.0](https://github.com/ccch1mneyyy/dsh-Go/releases/tag/v1.2.0) 下载 `dsh-go-1.2.0-arm64.apk` 到上级目录。以下命令校验官方 APK，再提取构建所需的 ARM64 运行时。

```sh
python3 ../dsh-android/fusion/prepare_runtime.py ../dsh-go-1.2.0-arm64.apk .
python3 tooling/check_runtime_bundle.py arm64
cd app
flutter pub get --enforce-lockfile
flutter build apk --release --flavor arm64 --target-platform android-arm64 \
  --build-name 1.2.0-fusion.1 --build-number 11230001 --dart-define=DSH_FUSION=true
```

安装包位于 `app/build/app/outputs/flutter-apk/app-arm64-release.apk`。运行时构建和依赖说明见 [DSH Go README](https://github.com/ccch1mneyyy/dsh-Go/blob/c1a84e3f833ad59ff07a5027994dae3935907a2d/README.md)。

## 已知限制

- 当前安装包面向 ARM64 Android。官方包和融合包在 x86_64 模拟器转译运行 PRoot 时都遇到 `libtalloc.so` 加载错误。DeepSeek 真账号、微信 8.0.79 长题与收发、QQ/TIM 收发及各厂商后台策略待真机验证。
- 视觉摘要使用应用公开的字族和系统提供的壁纸主色。自绘花字体继续依赖可用的截图与 OCR。
- GitHub 提供源码补丁。本地验收 APK 包含上游限制再分发的品牌素材，许可范围见 [DSH Go 素材说明](https://github.com/ccch1mneyyy/dsh-Go/blob/c1a84e3f833ad59ff07a5027994dae3935907a2d/ASSETS.md)。
