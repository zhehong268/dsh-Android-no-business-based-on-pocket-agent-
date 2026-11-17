# 更新记录

## [0.4.0] - 2026-11-17

- 添加任务运行面板，显示简短目标、下一步和停止按钮；空闲、完成和报错时移除。
- 节点点击使用一次实际触摸，避免 WebView 接受事件却不响应，也不在同次操作中补点。
- 初始屏幕、操作后和重新读屏都附图，截图编号优先分配给输入框和可操作控件。
- 普通导航不再继承页面上“发送”按钮的风险；微信发送只确认一次，付款和删除等操作继续确认。
- 授权文件改为只读，读取免逐次确认；禁用写文件和写答题卡，旧目录的持久写权限会尝试释放。

测试：946 项单测，0 失败、1 跳过；设备检查 29 项通过。[测试范围](docs/VALIDATION-2026-11-17.md)。

## [0.3.0] - 2026-11-17

### 新增

- 区域放大、精确查找、长按、双击、拖动和等待目标。
- 任务检查点、首页继续任务、已验证路线记忆。
- 经手机批准的 MCP 直接控制。

### 修复

- Pro 接收不了图片时，手机操作改用 Flash。
- WebView 不可靠的勾选状态标为未知，并附图。
- 拦截旧截图编号和重复空点，检查执行结果。
- 保留 DeepSeek 工具推理回放；修正连接重启时端口复用的顺序。

测试：938 项单测，0 失败、1 跳过；设备检查 15 项通过。详见 [测试记录](docs/VALIDATION-2026-11-16.md)。

## [0.2.0] - November 2026

### Added
* **Computer Link (MCP Bridge)**: Opt-in local HTTP MCP server on your Wi-Fi (off by default, pairing code required), allowing desktop coding agents (Claude Code, Cursor) to dispatch tasks to the phone with on-device approval.
* **Record My Routine**: Manual tap recorder that drafts a playbook from the buttons you tap, without typed text, passwords or personal-looking labels. It is saved privately on the phone and copied so you can review it before sharing.
* **One-Step Intent Shortcuts**: Instant direct dispatch for maps directions, phone calls, SMS composition, web searches, alarms, timers, URLs, and calendar events.
* **Pixel-Grid Visual Grounding**: Real-pixel grid overlay for vision models when accessibility trees report sparse (<3) nodes on custom Canvas or Flutter screens.
* **Quick Settings Pull-Down Tile**: Access Pocket Agent instantly from Android's notification pull-down shade.
* **Lighter Mode (experimental, off by default)**: Sends the AI only what changed on the screen each step.
* **Android 17 Compatibility**: Tested and verified on Android 16 and Android 17 emulators/devices.

### Changed
* Upgraded project license to **GNU Affero General Public License v3.0 (AGPL-3.0)**.
* Established community **Safety & Ethical Guidelines** ([ACCEPTABLE_USE.md](ACCEPTABLE_USE.md)).
* Added **Third-Party Notices** ([THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)) covering bundled fonts and dependencies.

---

## [0.1.0] - November 2026

### Added
* **Autonomous Floating Bubble**: On-device overlay that executes accessibility actions over any Android app with zero PC and zero ADB.
* **Bring-Your-Own-Key (BYOK) Engine**: Support for Anthropic Claude, OpenAI, OpenRouter, Gemini, DeepSeek, Groq, and Ollama running on your own machine.
* **Deterministic Safety Gate**: Android Keystore AES-256 GCM key encryption, blocked banking/auth apps, and mandatory human confirmation for irreversible actions (*Pay*, *Send*, *Delete*, *Order*).
* **Hands-Free Voice Loop**: Speech-to-text input, spoken action captions, and voice confirmations with *Talk back*.
* **Private Notification Listener**: On-device OTP lookup with automatic 6-digit code masking (`••••••`) and safe notification replies.
* **Bundled App Playbooks**: Heuristic guides for WhatsApp, Google Maps, YouTube, Gmail, and Chrome.
