# 阶段六：只读智能体对话闭环

## 1. 本阶段交付范围

本阶段在 `ticket-agent-service` 中建立可调用的对话入口，把前序阶段的会话记忆、主题路由、多模型降级、只读 MCP 工具和异步摘要串成一个完整闭环：

```text
网关鉴权
  -> 幂等写入用户问题和 RUNNING 轮次
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

### 2.2 普通完整回答

```http
POST /api/agent-service/chat
X-Request-Id: request-001
Idempotency-Key: request-001
Content-Type: application/json

{"conversationId":"...","message":"查询明天北京到上海的二等座"}
```

`X-Request-Id` 和 `Idempotency-Key` 均可省略。省略请求标识时服务端生成 32 位标识，省略幂等键时沿用请求标识。

### 2.3 SSE 流式回答

```http
POST /api/agent-service/chat/stream
Accept: text/event-stream
Content-Type: application/json

{"conversationId":"...","message":"G1 经过哪些站？"}
```

事件顺序为：

- `meta`：返回请求、会话、轮次和主题标识；
- `delta`：返回模型文本增量；
- `done`：返回最终完整回答；
- `error`：流已经建立后的安全失败分类和用户提示。

## 3. 幂等与轮次状态

相同请求标识或幂等键不会重复写入用户消息，也不会重复调用模型和 MCP 工具：

- 已完成轮次直接重放原回答，并在事件中标记 `reused=true`；
- 运行中轮次返回 `TURN_IN_PROGRESS`；
- 已失败或已取消轮次返回 `TURN_TERMINATED`，客户端应使用新请求标识重试；
- SSE 客户端主动断开时，仍在运行的轮次进入 `CANCELLED`；
- 模型、路由或工具失败时，轮次进入 `FAILED`，只保存稳定失败分类。

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

除阶段二至阶段五已有的数据库、模型平台和 MCP 环境变量外，本阶段没有新增密钥。要启用实时工具查询，仍需同时满足：

```text
TICKET_MCP_CLIENT_ENABLED=true
TICKET_MCP_SERVER_URL=http://127.0.0.1:9006
TICKET_MCP_INTERNAL_SECRET=<Agent 与 MCP 服务相同的内部密钥>
```

Agent 和 MCP 服务都必须启动，且 Agent 使用的 `12306_agent` 数据库已完成 Flyway V1、V2。
通过网关调用时还需要让 Agent 注册到 Nacos，例如设置 `NACOS_DISCOVERY_ENABLED=true`，并保证网关能够发现 `index12306-ticket-agent-service`。

## 7. 阶段边界

本阶段完成只读咨询闭环。下一阶段才能设计购票、退票、取消和支付等写操作，并必须增加参数草案、用户二次确认、确认令牌、状态机、幂等执行和补偿机制；不能直接把现有业务写接口暴露为自动工具。
