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

上面省略了 MCP 初始化。`b12` 仅作示例，实际使用时要换成刚返回的节点。
截图点击使用 `tap_mark`，同时传入该截图的 `observation`。重新读屏后，旧编号会失效。

| 用途 | 工具 |
| --- | --- |
| 观察 | `read_screen`、`screenshot`、`zoom_screen`、`find_elements`、`read_element` |
| 点击与输入 | `tap`、`tap_text`、`tap_mark`、`type` |
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
