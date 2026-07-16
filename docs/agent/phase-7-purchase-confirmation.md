# 阶段七：购票草案与显式确认执行

## 1. 阶段目标

本阶段在阶段六对话闭环上增加真实购票能力，但回答模型不能直接下单。完整链路为：

1. 模型调用本地 `prepare_ticket_purchase` 工具生成购票草案。
2. Agent 将草案写入独立数据库，并通过 JSON 或 SSE 返回结构化确认信息。
3. 用户调用确认接口提交一次性确认令牌。
4. Agent 在数据库事务内锁定草案、校验令牌、过期时间和幂等键，并领取唯一执行权。
5. 专用执行器调用不向回答模型暴露的 `execute_confirmed_ticket_purchase` MCP 工具。
6. MCP 服务再次校验 HMAC 身份、操作标识和参数指纹，再调用现有票务购票接口。
7. Agent 保存脱敏结果；网络或超时导致结果无法确定时标记为 `UNKNOWN`，禁止自动重试。

退票、取消订单和支付尚未在本阶段开放。它们后续可以复用操作草案、确认令牌和执行审计状态机。

## 2. 安全边界

- `prepare_ticket_purchase` 是模型可见工具，只写 Agent 数据库，不访问购票接口。
- `execute_confirmed_ticket_purchase` 只存在于专用 MCP 执行器，不加入回答模型的 `ToolCallbackProvider`。
- 用户身份只来自网关注入的 `userId` 和 `username` 请求头。
- 确认令牌使用 HMAC-SHA256，覆盖用户、会话、主题、轮次、操作、参数指纹和有效期。
- MCP 签名新增 `actionId` 和 `payloadHash`，防止确认后替换车次、站点、乘车人或席别。
- MCP 服务调用购票接口前会查询当前用户乘车人列表，拒绝其他用户的乘车人标识。
- 购票结果只保留订单号、席别、车厢、座位、姓名、票种和金额，不保存证件号。

## 3. 数据库变更

Flyway 迁移文件：

`ai-services/ticket-agent-service/src/main/resources/db/migration/V3__create_agent_action_tables.sql`

新增表：

- `t_agent_action_draft`：保存不可变草案、确认有效期、状态和脱敏结果。
- `t_agent_action_execution`：保存确认请求、幂等键、执行结果和失败分类。

关键唯一约束：

- 同一轮次和操作类型只能存在一份草案。
- 一份草案只能创建一条执行记录。
- 一个确认幂等键只能被使用一次。

## 4. 配置

Agent 服务新增环境变量：

```text
TICKET_AGENT_CONFIRMATION_SECRET=至少32个字符的随机密钥
```

如果未单独配置，当前配置会回退使用 `TICKET_MCP_INTERNAL_SECRET`。生产环境建议为确认令牌配置独立密钥。

真实购票需要同时启用 MCP 客户端：

```text
TICKET_MCP_CLIENT_ENABLED=true
TICKET_MCP_SERVER_URL=http://127.0.0.1:9006
TICKET_MCP_INTERNAL_SECRET=Agent与MCP服务一致的至少32字符密钥
```

## 5. 对话响应协议

普通 JSON `/api/agent-service/chat` 在生成购票草案时增加 `action` 字段：

```json
{
  "content": "请核对购票信息并确认",
  "action": {
    "actionId": "操作标识",
    "actionType": "TICKET_PURCHASE",
    "status": "AWAITING_CONFIRMATION",
    "summary": "购票摘要",
    "confirmationExpiresAt": "2026-07-16T08:00:00Z",
    "confirmationToken": "一次性确认令牌"
  }
}
```

SSE `/api/agent-service/chat/stream` 在 `done` 前增加 `action_required` 事件。确认令牌不进入模型回答正文。

## 6. 确认与查询接口

确认购票：

```http
POST /api/agent-service/actions/{actionId}/confirm
userId: 当前用户标识
username: 当前用户名
X-Request-Id: 可选请求标识
Idempotency-Key: 建议由客户端持久化并在网络重试时复用
Content-Type: application/json

{
  "confirmationToken": "action_required事件中的令牌"
}
```

查询操作状态：

```http
GET /api/agent-service/actions/{actionId}
userId: 当前用户标识
```

状态含义：

- `AWAITING_CONFIRMATION`：等待用户确认。
- `EXECUTING`：已经领取执行权，不能重复提交。
- `SUCCEEDED`：购票成功，可读取脱敏订单结果。
- `UNKNOWN`：下游结果无法确定，应先查询本人订单，禁止自动重试。
- `EXPIRED`：确认令牌已过期，需要重新发起对话生成草案。

## 7. 启动顺序

1. 执行 V3 数据库迁移或让 Agent 服务通过 Flyway 自动迁移。
2. 配置 Agent 与 MCP 共用的内部签名密钥，以及 Agent 确认密钥。
3. 先启动票务、用户、订单等原有业务服务。
4. 启动 `ticket-mcp-server`，确认日志显示注册 6 个工具。
5. 启动启用了 MCP 客户端的 `ticket-agent-service`。
6. 通过查询乘车人、查询余票、生成草案、确认购票、查询订单的顺序执行联调。

## 8. 验证结果

已执行：

```text
.\mvnw.cmd -pl ai-services/ticket-agent-service,ai-services/ticket-mcp-server -am test
```

测试覆盖草案持久化、合法令牌单次执行、重复确认结果复用、伪造令牌拒绝、MCP 签名协议和购票参数指纹防篡改。
