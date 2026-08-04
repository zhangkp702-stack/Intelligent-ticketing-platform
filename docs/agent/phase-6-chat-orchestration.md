# 阶段六：只读智能体对话闭环

## 1. 本阶段交付范围

本阶段在 `ticket-agent-service` 中建立可调用的对话入口，把前序阶段的会话记忆、主题路由、多模型降级、只读 MCP 工具和异步摘要串成一个完整闭环：

```text
网关鉴权
  -> 服务端预创建 DRAFT 轮次并签发短期提交令牌
  -> 首次提交原子绑定问题指纹、用户消息和 RUNNING 执行租约
  -> 根据摘要卡片和最近用户问题选择主题
  -> 加载命中主题的摘要、结构化状态和最近完整对话
  -> ANSWER_TOOL 多模型流式降级
  -> Spring AI 自动调用只读 MCP 工具
  -> SSE 增量输出
  -> 原子写入助手消息并完成轮次
  -> 事务提交后异步触发主题摘要
```

购票、退票、取消订单和支付仍未开放。本阶段只能查询车票、经停站、当前用户乘车人和当前用户订单。

## 2. 对外接口

所有接口统一位于 `/api/agent-service`，正常使用时应通过网关访问。网关完成 Token 校验并注入 `userId` 和 `username`，请求体不能指定或覆盖用户身份。

### 2.1 创建会话

```http
POST /api/agent-service/conversations
Content-Type: application/json

{"title":"暑假出行咨询"}
```

返回：

```json
{"conversationId":"..."}
```

### 2.2 预创建服务端轮次

```http
POST /api/agent-service/conversations/{conversationId}/turns
```

返回：

```json
{
  "turnId": "服务端轮次标识",
  "submissionToken": "绑定用户、会话、轮次和截止时间的 HMAC 令牌",
  "expiresAt": "2026-08-03T08:00:00Z"
}
```

前端必须在用户点击发送时先调用该接口，并在同一业务轮次的网络重试中复用 `turnId`、`submissionToken` 和原始问题。`turnId` 不是浏览器生成的请求 ID。

### 2.3 SSE 流式回答

```http
POST /api/agent-service/turns/{turnId}/stream
Accept: text/event-stream
X-Attempt-Id: 本次网络尝试标识
Content-Type: application/json

{
  "conversationId":"...",
  "message":"G1 经过哪些站？",
  "submissionToken":"预创建接口返回的令牌"
}
```

`X-Attempt-Id` 只用于区分 HTTP/SSE 网络尝试，不是业务幂等键。业务幂等边界始终是服务端 `turnId`。

事件顺序为：

- `meta`：返回请求、会话、轮次和主题标识；
- `delta`：返回模型文本增量；
- `done`：返回最终完整回答；
- `error`：流已经建立后的安全失败分类和用户提示。

### 2.4 查询轮次状态

```http
GET /api/agent-service/turns/{turnId}
```

该接口返回 `DRAFT`、`RUNNING`、`COMPLETED`、`FAILED` 或 `CANCELLED`。网络断流后前端先查询该接口；若已 `COMPLETED`，直接恢复数据库中的最终回答。

## 3. 幂等与轮次状态

轮次由服务端生成，首次提交通过数据库写锁完成 `DRAFT -> RUNNING`，并固化标准化问题的 SHA-256 指纹：

- 相同 `turnId` 和相同问题只复用原用户消息；
- 相同 `turnId` 携带不同问题返回 `TURN_PAYLOAD_MISMATCH`；
- 伪造、跨用户或过期提交令牌不能启动 DRAFT 轮次；
- 已完成轮次直接重放原回答，并在事件中标记 `reused=true`；
- 运行中轮次返回 `TURN_IN_PROGRESS`；
- 已失败或已取消轮次返回 `TURN_TERMINATED`，新的业务尝试必须重新向服务端创建轮次；
- SSE 客户端主动断开时，仍在运行的轮次进入 `CANCELLED`；
- 模型、路由或工具失败时，轮次进入 `FAILED`，只保存稳定失败分类。

`RUNNING` 轮次同时记录执行实例、租约截止时间和递增 fencing token。流水线按租约三分之一周期续租；数据库中的取消、租约过期或其他实例接管会让旧执行者停止。最终回答、失败和断流取消都必须携带领取时的 owner 与 fencing token，旧连接不能覆盖新执行者的状态。本阶段不把 Redis 锁或一次 SSE 连接当作最终真相。

### 3.1 可恢复任务检查点

任务规划完成后，模型产生的临时 `taskId` 会在同一事务内改写为服务端主键，并把依赖关系一起固化到 `t_agent_task_execution`。每个任务独立记录：

- 不可变计划和服务端依赖；
- `PENDING -> RUNNING -> 终态` 状态和尝试次数；
- 去除临时确认令牌后的结构化结果和错误信息。

节点宕机后，后台恢复器扫描租约到期的 `RUNNING` Turn，并通过原始 `startTurn` 行锁竞争接管。接管成功会提升 Turn fencing token、复用原任务计划和终态结果，只重新执行尚未完成的任务。所有 Task 状态更新都必须先锁定并校验所属 Turn 的执行者与 fencing token，因此旧实例恢复后的迟到回调没有写权限，也不需要再维护一套重复的 Task 租约。

跨实例取消不依赖本地内存映射：取消接口先写数据库终态，原执行实例的心跳随后检测到执行权失效并停止模型或工具流。同实例仍保留本地取消信号以缩短停止延迟。

## 4. Spring AI 与 MCP 调用

回答模型运行时使用 `OpenAiChatOptions` 注册容器中已启用的 `ToolCallbackProvider`。MCP 未启用时工具列表为空，模型只能说明实时查询暂不可用。

工具开启时，模型候选必须同时声明 `STREAMING` 和 `TOOL_CALLING` 能力。每次回答调用显式传入：

- `requestId`；
- `userId` 和 `username`；
- `conversationId`；
- `turnId`；
- 路由确定后的 `topicId`。

这些字段由 `McpToolContextFactory` 创建，不使用 `ThreadLocal`，也不允许模型修改。工具并行调用被关闭，避免同一回答内产生不必要的并发下游请求。

## 5. 安全和异常边界

- 系统提示明确把用户输入、历史摘要和工具结果视为不可信数据；
- 回答上下文只还原文本用户消息和助手消息，工具正文不作为下一轮指令；
- HTTP 和 SSE 错误不回传模型平台、MCP、数据库的原始异常正文；
- 模型调用审计现在可关联请求、会话、主题和轮次；
- 网关的开发与聚合配置都为 `/api/agent-service/**` 增加 Token 校验。

生产部署还应限制 9005 端口只允许网关和内部运维网络访问，避免外部请求绕过网关注入身份头。

## 6. 启动条件

除阶段二至阶段五已有的数据库、模型平台和 MCP 环境变量外，轮次提交协议还需要独立 HMAC 密钥：

```text
TICKET_AGENT_TURN_SECRET=<至少 32 字节的随机密钥>
```

未单独配置时会回退到确认密钥或 MCP 内部密钥，但生产环境建议独立配置。要启用实时工具查询，仍需同时满足：

```text
TICKET_MCP_CLIENT_ENABLED=true
TICKET_MCP_SERVER_URL=http://127.0.0.1:9006
TICKET_MCP_INTERNAL_SECRET=<Agent 与 MCP 服务相同的内部密钥>
```

Agent 和 MCP 服务都必须启动，且 Agent 使用的 `12306_agent` 数据库已完成 Flyway V1 至 V7。V7 新增持久化任务执行表，并为 Turn 固化恢复所需用户名。
通过网关调用时还需要让 Agent 注册到 Nacos，例如设置 `NACOS_DISCOVERY_ENABLED=true`，并保证网关能够发现 `index12306-ticket-agent-service`。

## 7. 阶段边界

本阶段完成只读咨询闭环。下一阶段才能设计购票、退票、取消和支付等写操作，并必须增加参数草案、用户二次确认、确认令牌、状态机、幂等执行和补偿机制；不能直接把现有业务写接口暴露为自动工具。
