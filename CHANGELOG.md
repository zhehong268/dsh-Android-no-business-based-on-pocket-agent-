# 更新记录

## [0.9.0] - 2026-11-18

- 首页增加独立“聊天答疑”，不需要无障碍权限，不执行手机工具。
- 支持连续追问、复制回答、停止生成、失败重试、修改问题及清空会话；可见记录保存在本机，重启后恢复。
- 聊天沿用设置中的模型，两种协议在无工具会话中省略工具字段，并保留正常多轮历史；长会话按预算恢复可见上下文。
- 聊天与手机任务分别保存进度，任务失败继续原任务；聊天清空和停止不会改动任务恢复记录。

## [0.8.0] - 2026-11-18

- 失败记录改为“继续任务”，恢复原目标、已完成步骤和原应用；分别保留最近 12 个未完成任务，长任务原文不再受记录标题截断影响。
- 发送核验支持输入框重建或消失、无障碍描述及多段正文，保存正文摘要值以便中断后继续核验；结果未明仍阻止重发。
- 页面稳定等待封顶 300 毫秒，安静页面使用 60 毫秒防抖；刷新根节点，发送后直接复查，去掉连续动作中的重复读屏与中间截图。
- 截图限频只等待剩余间隔，取消固定一秒重试。
- 运行悬浮窗的标题、正文及空白区域均可拖动；拖动有阈值且限制在屏幕内，停止按钮独立响应。

## [0.7.0] - 2026-11-17

- 新增运行控件原文、扩展控件树与字体参照；观察附带字体缩放、字重、壁纸主色及可见背景采样。
- 新增联系人导航、聊天输入框定位和草稿填写，核对会话标题、重名备注与语音模式，缺失正文控件时读取扩展结构。
- 输入优先整段粘贴，保留空格、大小写、换行与 emoji，不点击用户键盘；派发后不重复写入未核验的草稿。
- 修复确认卡片移除前遮挡底部发送的问题；预览实际完整草稿，长内容可滚动，消息核验拒绝重复或额外正文和发送中状态。
- 增加独立原生测试应用，覆盖花体、无标签与隐藏输入框、同名联系人、深浅背景、大字体和不同分辨率。

测试：1,060 项单测，0 失败、3 项外网测试跳过；Debug、Release Kotlin 编译和 Lint 通过。[设备与真实模型测试范围](docs/VALIDATION-0.7.0.md)。

## [0.6.0] - 2026-11-17

- DeepSeek 明确开启思考：操作使用 high，独立复核使用 max；补齐多轮推理回放与带签名内容保留。
- 增加思考输出预算，截断时重试一次，拒绝执行不完整工具；截图按图像估算上下文，只保留最近两张。
- 新增 `perform_steps`，连续执行最多四步安全动作，每步重新观察和定位，异常时停止剩余步骤。
- 填写和文字消息发送通过本地核验后可直接收尾，减少完成报告的额外请求；普通任务不再因字数稍长就提前复核。
- 减少空闲页面重复等待，任务面板事件不延长页面稳定时间；网络错误按域名、超时、连接和协议区分。
- MCP 任务结果附决策次数、手机动作数、模型耗时与总耗时，方便定位停顿。

测试：1,018 项单测，0 失败、1 跳过；包含真实 DeepSeek 两种协议的看图与多轮工具测试。独立模拟器 38 项操作检查通过。[测试范围](docs/VALIDATION-0.6.0.md)。

## [0.5.0] - 2026-11-17

- 控件查询支持类型、可输入状态和资源 ID；文字匹配兼容全角字符与空白，列表可向上查找。
- 长页面优先保留输入框和按钮，输入提示词与实际内容分开。
- 文字消息按新增气泡、输入框清空及失败标记核验；结果不明不重发，中断恢复保留未确认状态。
- 发送按钮禁止双击，不可用控件不弹确认；无标签坐标同时核对窗口、资源 ID 与边界。
- 连续失败或重复来回切页时最多自动复核两次路线，复核用量计入任务；历史路线按任务相关性检索。

测试：984 项单测，0 失败、1 跳过；独立模拟器 35 项检查通过。[测试范围](docs/VALIDATION-0.5.0.md)。

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
