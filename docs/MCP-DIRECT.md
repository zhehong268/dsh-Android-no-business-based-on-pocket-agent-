# 电脑直接操作手机

手机设置中打开“电脑连接”，读取局域网地址与配对码。电脑与手机需要互通。

连接地址为 `http://<手机IP>:8765/mcp`，传输使用 Streamable HTTP，每个请求携带：

```text
Authorization: Bearer <手机显示的配对码>
Content-Type: application/json
```

在支持 HTTP MCP 的客户端中填写该地址和认证头。不同客户端的配置格式不同，不需要改手机源码。
没有在本轮修改或重启用户的桌面 DSH/Codex 配置；本轮验证的是手机端 MCP 接口。

## 两种模式

- `run_task(task)`：把任务交给手机模型执行；手机主人先批准，再按普通手机任务运行。
- `control_start(task)`：申请让电脑模型直接选动作；手机先显示本次目标，批准后返回当前屏幕，电脑继续逐步调用工具。

直接模式支持 `read_screen`、`screenshot`、`zoom_screen`、`find_elements`、`read_element`、`tap`、`tap_text`、`tap_mark`、`type`、`scroll`、`wait_for`、`back`、`home`、`open_app`、`long_press`、`double_tap`、`drag`。
状态和停止使用 `control_status`、`control_stop`。

例子（省略初始化往返）：

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"control_start","arguments":{"task":"查看设置里的提醒开关状态"}}}
```

批准后按返回的**最新**节点操作：

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_screen","arguments":{}}}
```

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"tap","arguments":{"node_id":"b12"}}}
```

`b12` 只是示例，必须换成刚返回屏幕中的节点。截图编号必须同时带 `observation`。
截图以 MCP 原生 `image` 内容返回，客户端需要支持图像工具结果。

## 行为约束

- 未批准直接模式前不能读屏或点击；原有配对认证、来源限制和请求大小限制仍在。
- 本地任务、远程任务和直接控制互斥。每次只执行一个电脑动作。
- 电脑动作复用手机同一个工具解析、目标重校验、安全门和确认卡片；不会另建一条免确认执行通道。
- 发送、发布、删除、拖动等风险按原规则确认；锁屏和服务停止会阻止操作。
- 两分钟没有下一步操作会结束控制；`control_stop`、手机任务停止或关闭电脑连接也会结束。
- 断线或超时不代表上一步没有执行。先重新核对页面，尤其不能直接重发消息或重复提交。
- 一次直接会话最多 60 步；复杂任务应分段执行并明确每段结果。

配对码不要写进仓库或公开日志。目前网络传输没有 TLS，使用可信局域网或自行建立安全隧道。
