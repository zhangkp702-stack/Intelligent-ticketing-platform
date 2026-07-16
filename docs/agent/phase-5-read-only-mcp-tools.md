# 阶段五：只读票务 MCP 工具与安全调用链

## 1. 本阶段交付范围

本阶段完成 `ticket-agent-service` 到 `ticket-mcp-server` 再到现有业务服务的只读工具链，注册以下五个工具：

| 工具 | 下游接口 | 数据边界 |
| --- | --- | --- |
| `resolve_station` | `GET /api/ticket-service/region-station/query` | 最多 10 个站点候选 |
| `query_tickets` | `GET /api/ticket-service/ticket/query` | 最多 20 个车次，席别字段白名单 |
| `query_train_stops` | `GET /api/ticket-service/train-station/query` | 最多 100 个经停站 |
| `list_my_passengers` | `GET /api/user-service/passenger/query` | 最多 30 人，不返回真实证件号和真实手机号 |
| `list_my_orders` | `GET /api/order-service/order/ticket/self/page` | 每页最多 20 条，只查询当前用户 |

购票、取消、支付和退票工具没有注册，也不会因为 MCP 服务以后增加工具而自动进入 Agent。Agent 端使用固定白名单二次过滤工具发现结果。

## 2. 调用链与身份安全

```text
AgentRequestContext
  -> ChatClient toolContext（阶段六编排时传入）
  -> HMAC-SHA256 签名 MCP _meta
  -> Stateless Streamable HTTP /mcp
  -> 校验时间戳、随机数、防重放和签名
  -> 生成可信 McpCallerIdentity
  -> 注入 userId / username 请求头
  -> ticket-service / user-service / order-service
```

`userId`、`username`、`conversationId`、`turnId` 和 `topicId` 不属于模型可填写的工具参数。Spring AI 在实际工具调用时把 `toolContext` 转换到 MCP `_meta`，MCP 服务验证通过后才允许访问业务服务。

当前防重放随机数缓存在单个 MCP 实例内。多实例部署时仍应依赖内部网络访问控制；如果以后对写工具使用同一机制，需要将随机数防重放升级为 Redis 等跨实例存储。

## 3. 必需环境变量

Agent 和 MCP 服务必须配置相同的高强度内部密钥，密钥不得写入 Git：

```text
TICKET_MCP_INTERNAL_SECRET=<至少 32 个字符的随机密钥>
```

启动 `ticket-mcp-server` 时可以按部署环境覆盖业务服务地址：

```text
TICKET_SERVICE_URL=http://127.0.0.1:9002
USER_SERVICE_URL=http://127.0.0.1:9001
ORDER_SERVICE_URL=http://127.0.0.1:9003
```

确认 MCP 服务启动并可访问后，在 `ticket-agent-service` 中启用客户端：

```text
TICKET_MCP_CLIENT_ENABLED=true
TICKET_MCP_SERVER_URL=http://127.0.0.1:9006
```

开发环境默认关闭 Agent MCP 客户端，避免 MCP 服务尚未启动时阻塞 Agent 的记忆、模型路由等独立功能。`ticket-mcp-server` 自身缺少内部密钥时会启动失败。

## 4. 工具调用审计

Flyway `V2__create_agent_tool_call_table.sql` 新增 `t_agent_tool_call`。每次 Agent MCP 工具调用记录：

- 请求、会话、主题和轮次标识；
- 工具名称、调用序号、结果和耗时；
- 工具参数的 SHA-256 指纹；
- 可识别的响应条目数量；
- 失败类别和异常类型。

审计表不保存工具参数 JSON、工具响应正文、证件号、手机号或密钥。审计写入使用独立事务，审计数据库故障不会覆盖真实工具结果。

## 5. 数据库升级

如果 `ticket-agent-service` 使用的 `12306_agent` 数据库由 Flyway 管理，服务启动时会自动执行 V2。手工管理数据库时，应在同一个 `12306_agent` 库执行：

```text
ai-services/ticket-agent-service/src/main/resources/db/migration/V2__create_agent_tool_call_table.sql
```

执行后应存在 `t_agent_tool_call`，Flyway schema 版本应为 2。

## 6. 阶段边界

本阶段只建立工具能力和安全调用链，还没有聊天 Controller、SSE 输出、回答模型编排或确认式写操作。阶段六需要把主题路由、上下文加载、多模型回答和本阶段的 `ToolCallbackProvider` 串成完整对话入口，并在每次回答调用中显式传入 `McpToolContextFactory` 生成的工具上下文。
