# 阶段九：取消订单与退票显式确认闭环

## 1. 阶段目标

本阶段在阶段七通用操作状态机和阶段八订单安全接口基础上，完成取消订单与退票的完整智能体写操作闭环：

1. 回答模型只能调用本地草案工具。
2. Agent 直接调用可信只读 MCP 预览，不采信模型提供的订单状态或退款金额。
3. Agent 将不可变业务快照写入独立数据库，并返回结构化确认信息。
4. 用户通过一次性确认令牌显式确认。
5. Agent 在消费令牌前重新预览，拒绝状态、车票范围或金额已经变化的草案。
6. 专用执行器调用不向回答模型暴露的取消或退票 MCP 写工具。
7. MCP 服务校验签名身份、操作标识和参数指纹后，复用现有票务服务写接口。
8. Agent 保存脱敏结果；网络或响应异常导致结果无法确定时标记为 `UNKNOWN`，禁止自动重试。

## 2. 模型可见工具

新增两个本地 Spring AI 草案工具：

- `prepare_order_cancellation`：读取实时取消预览并生成取消订单草案。
- `prepare_ticket_refund`：读取实时可退范围和预计退款金额并生成退票草案。

这两个工具只写入 `12306_agent` 数据库，不调用真实取消或退款接口。

回答模型继续可以使用以下只读 MCP 工具收集参数：

- `list_my_orders`
- `get_my_order_detail`
- `preview_order_cancellation`
- `preview_ticket_refund`
- `query_pay_status`

模型提示增加以下约束：

- 取消或退票前先查询本人订单详情。
- 部分退票必须使用订单详情返回的子订单记录 ID。
- 不得猜测订单号、退票范围或退款金额。
- 不得声称已经取消或退票，也不得尝试调用真实写工具。

## 3. 隐藏写工具

MCP Server 新增两个不进入回答模型白名单的写工具：

- `execute_confirmed_order_cancellation`
- `execute_confirmed_ticket_refund`

Agent 通过独立 `ConfirmedTicketOperationMcpExecutor` 发现并调用它们。回答模型使用的 `McpToolFilter` 仍只允许九个只读工具，因此服务端即使注册了写工具，模型也无法发现。

MCP Server 当前共注册十二个工具：

- 九个只读查询或预览工具。
- 一个已确认购票工具。
- 一个已确认取消工具。
- 一个已确认退票工具。

## 4. 草案快照与二次预览

取消草案保存：

```json
{
  "orderSn": "订单号",
  "orderStatus": 10
}
```

退票草案保存：

```json
{
  "orderSn": "订单号",
  "type": 0,
  "orderItemIds": ["子订单记录ID"],
  "expectedRefundAmount": 5000
}
```

用户确认时，Agent 在消费确认令牌之前再次调用只读预览：

- 取消订单：订单号、订单状态和 `canCancel` 必须保持一致。
- 退票：订单号、退款类型、选中车票范围、`refundable` 和预计退款金额必须保持一致。

任一关键字段变化时返回 `ACTION_PREVIEW_CHANGED`，草案继续保持 `AWAITING_CONFIRMATION`，用户需要重新生成草案。

真实写接口仍会再次执行订单归属、订单状态、退款范围和支付退款校验，因此二次预览不是唯一业务安全检查。

## 5. 状态机与数据库

本阶段不新增数据库表，也不新增 Flyway 迁移。

继续复用阶段七：

- `t_agent_action_draft`
- `t_agent_action_execution`

新增动作类型：

- `TICKET_CANCEL`
- `TICKET_REFUND`

状态存储层调整为同一回答轮次只允许产生一个高风险操作草案。模型重试完全相同的工具参数时复用已有草案；尝试在同一轮次替换动作类型或参数时拒绝。

确认令牌和 MCP 元数据中的 `payloadHash` 已覆盖动作类型对应的完整草案 JSON，确认后不能替换订单号、订单状态、退票范围或预计金额。

## 6. 执行结果与不确定状态

取消结果只保存：

- 订单号。
- 是否已提交取消。

退票结果只保存：

- 幂等退款请求标识。
- 订单号。
- 退款类型。
- 实际退款金额。
- 退款状态。

第三方退款交易凭证不会返回 Agent，也不会写入操作结果。

真实写请求发出后发生超时、网络中断或响应解析失败时：

- 取消标记为 `CANCELLATION_RESULT_UNKNOWN`。
- 退票标记为 `REFUND_RESULT_UNKNOWN`。
- 不自动重复调用写工具。
- 用户应先查询本人订单和支付状态，再决定后续处理。

## 7. 接口兼容

确认和状态查询接口不变：

```http
POST /api/agent-service/actions/{actionId}/confirm
GET  /api/agent-service/actions/{actionId}
```

状态响应新增 `actionType`，用于区分：

- `TICKET_PURCHASE`
- `TICKET_CANCEL`
- `TICKET_REFUND`

`result` 根据动作类型返回对应的脱敏结构。

## 8. 验证结果

已执行：

```text
.\mvnw.cmd -pl ai-services/ticket-agent-service,ai-services/ticket-mcp-server -am test
```

验证结果：

- Agent 服务测试 36 个，全部通过。
- MCP Server 测试 9 个，全部通过。
- MCP Server 启动测试确认注册 12 个工具。

新增测试覆盖：

- 取消订单经过预览和显式确认后只执行一次。
- 重复确认复用持久化成功结果。
- 退票金额变化时拒绝消费确认令牌。
- 取消订单签名参数防篡改。
- 退票范围和预计金额签名防篡改。

## 9. 后续阶段

下一阶段应进行真实环境端到端联调和结果核对：

1. 使用真实 MySQL、MCP Server、票务、订单和支付服务验证三类确认流程。
2. 验证服务重启、网络超时和 `UNKNOWN` 状态下的人工核对路径。
3. 增加操作成功率、预览变化、确认过期和不确定结果指标。
4. 根据联调结果决定是否增加执行事件表和自动对账任务，但不得对不确定写请求进行盲目重试。
