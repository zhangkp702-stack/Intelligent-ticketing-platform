# 智能体高风险操作可观测性与验收

## 1. 观测范围

购票、取消订单和退票继续通过统一确认状态机执行。指标只使用操作类型、执行终态和固定拒绝原因等低基数标签，不记录用户标识、请求标识、操作标识、订单号、异常正文或草案参数。

操作类型：

- `TICKET_PURCHASE`
- `TICKET_CANCEL`
- `TICKET_REFUND`

## 2. 指标

| 指标 | 类型 | 标签 | 用途 |
|---|---|---|---|
| `agent.action.executions` | Counter | `actionType`, `outcome` | 统计真实写调用的成功、明确失败和结果未知次数 |
| `agent.action.execution.duration` | Timer | `actionType`, `outcome` | 统计真实写调用耗时 |
| `agent.action.confirmation.rejections` | Counter | `actionType`, `reason` | 统计确认前被拒绝的原因 |
| `agent.action.confirmation.expirations` | Counter | `actionType` | 统计草案首次进入过期终态的次数 |

执行终态：

- `SUCCEEDED`
- `FAILED`
- `UNKNOWN`

确认拒绝原因：

- `EXPIRED`
- `PREVIEW_CHANGED`
- `INVALID_TOKEN`
- `NOT_CONFIRMABLE`

Actuator 已开放 `metrics` 端点，可以按指标名称查询，例如：

```text
GET /actuator/metrics/agent.action.executions
GET /actuator/metrics/agent.action.execution.duration
GET /actuator/metrics/agent.action.confirmation.rejections
GET /actuator/metrics/agent.action.confirmation.expirations
```

## 3. 日志

确认执行日志只保留内部追踪字段：

- 成功：`requestId`、`actionId`、`actionType`
- 明确失败：`requestId`、`actionId`、`failureCategory`、`exceptionType`
- 结果未知：`requestId`、`actionId`、`failureCategory`、`exceptionType`
- 快照变化：`requestId`、`actionId`、`actionType`、`failureCategory`

不得把确认令牌、用户输入、乘车人证件信息、原始 MCP 响应或异常正文写入日志。

## 4. 验收口径

- 成功率：`SUCCEEDED / (SUCCEEDED + FAILED + UNKNOWN)`
- 结果未知比例：`UNKNOWN / (SUCCEEDED + FAILED + UNKNOWN)`
- 快照变化次数：`reason=PREVIEW_CHANGED`
- 确认过期次数：`agent.action.confirmation.expirations`
- 重复确认返回已保存结果，不重复增加真实执行次数
- 确认在重新预览后跨过截止时间时，数据库仍持久化 `EXPIRED`，且过期指标只增加一次

自动化测试覆盖上述指标、结果重放和过期事务边界。部署环境仍应使用真实 MySQL、MCP Server、票务、订单及支付服务各执行一次购票、取消和退票，并核对状态接口、业务订单和指标增量一致。
