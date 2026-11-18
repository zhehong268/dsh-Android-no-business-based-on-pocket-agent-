# 电脑连接

在手机设置中打开“电脑连接”，复制地址和配对码。电脑与手机需要网络互通。

客户端使用 Streamable HTTP：

```text
地址：http://<手机IP>:8765/mcp
Authorization: Bearer <配对码>
Content-Type: application/json
```

## 发送任务

`run_task` 把任务交给手机模型。手机上批准后开始执行，结束时返回结果。

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"run_task","arguments":{"task":"查看今天的日程"}}}
```

## 逐步操作

先调用 `control_start`，说明这次要做什么。手机批准后，接口返回当前屏幕。

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"control_start","arguments":{"task":"查看提醒开关状态"}}}
```

随后按最新屏幕中的节点调用工具：

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_screen","arguments":{}}}
```

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"tap","arguments":{"node_id":"b12"}}}
```

`find_elements` 可组合 `text`、`role`、`editable`、`view_id` 和容器 `node_id`。找无标签输入框可传 `{"editable":true}`；`tap_text` 支持 `direction:"up"`，默认向下。

只需核对文字和控件状态时，在 `read_screen` 的 `arguments` 传 `{"include_image":false}`，跳过截图。每次读取都会更新节点编号。

在聊天应用中，`fill_chat({recipient:"张小明",text:"明天十点见",open_if_needed:true})` 可以查人、核对会话并填写草稿。重名时按候选备注补充用户给的 `hint`。只需要打开会话时用 `open_contact`。只需要查输入框时用 `find_chat_input`。填写会在必要时从语音切回文字，发送须单独确认。

`read_ui_source` 返回运行控件原始文字、类名、资源 ID 和边界，并在本次任务中包含更多非重要控件。`font_reference({text:"张小明 发送"})` 加载公开字体生成参照图；它不是屏幕截图，调用后须重新观察再点击。私有字体、数据库与反编译源码不在读取范围内。

已知下一步目标时，可用 `perform_steps` 减少往返；每步按当前页面重新找目标，不携带旧节点编号。最多四步，只支持 `tap`、`type`、`scroll`、`back`、`open_app`。例如打开会话并填写草稿：

```json
{"steps":[{"action":"tap","target":{"text":"测试联系人"},"expect_text":"消息输入框"},{"action":"type","target":{"editable":true,"view_id":"message"},"text":"明天十点见","expect_text":"明天十点见"}]}
```

`target` 可组合 `text`、`role`、`editable`、`view_id`。重名、失败、状态未知或需要确认时停止，剩余动作不执行；发送要单独调用。
本地模型的最终动作可用 `finish_if_verified` 请求核验后收尾。连续操作还必须带 `summary` 和最后一步的 `expect_text`；直接控制仍由 `control_stop` 结束。

上面省略了 MCP 初始化。`b12` 仅作示例，实际使用时要换成刚返回的节点。
截图点击使用 `tap_mark`，同时传入该截图的 `observation`。重新读屏后，旧编号会失效。

| 用途 | 工具 |
| --- | --- |
| 观察 | `read_screen`、`read_ui_source`、`screenshot`、`zoom_screen`、`find_elements`、`read_element`、`font_reference` |
| 聊天 | `open_contact`、`find_chat_input`、`fill_chat` |
| 点击与输入 | `tap`、`tap_text`、`tap_mark`、`type`、`perform_steps` |
| 手势与导航 | `scroll`、`long_press`、`double_tap`、`drag`、`back`、`home`、`open_app` |
| 等待 | `wait_for` |
| 连接管理 | `control_status`、`control_stop` |

截图通过 MCP 的 `image` 内容返回，电脑客户端需要支持图片工具结果。

## 停止和超时

调用 `control_stop`、点击手机运行面板的“停止”，或关闭电脑连接，都会结束控制。任务停止后面板移除。
两分钟没有下一步操作也会结束。一次直接控制最多 60 步。

本地任务和电脑控制不能同时运行。发送、删除、拖动等操作仍走手机确认；锁屏后停止操作。
断线时先核对上一步结果，尤其不要直接重发消息或重复提交。

连接默认关闭，目前没有 TLS。配对码不要写入仓库，8765 端口不要开放到公网。
手机接口已做设备测试，桌面 MCP 客户端需要自行配置。

`run_task` 结果的 `_meta.dsh` 包含 `model_steps`、`native_actions`、`model_ms`、`total_ms` 和 `tokens`。次数指操作模型决策和手机动作，模型耗时还包括独立复核；总耗时不含最初批准电脑任务的等待。缓存输入已包含在输入用量中，不重复累加。
