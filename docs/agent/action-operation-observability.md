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
| `agent.action.reconciliation.outbox.publications` | Counter | `outcome` | 统计对账 Outbox 向 RocketMQ 发布的成功和失败次数 |
| `agent.action.reconciliation.outbox.publication.duration` | Timer | `outcome` | 统计 Broker 同步发送和本地确认耗时 |
| `agent.action.reconciliation.inbox.claims` | Counter | `outcome` | 统计 Inbox 成功领取、重复忽略和契约拒绝次数 |
| `agent.action.reconciliation.probes` | Counter | `actionType`, `outcome` | 统计权威只读查询返回的成功、失败、处理中和异常 |
| `agent.action.reconciliation.probe.duration` | Timer | `actionType`, `outcome` | 统计权威只读查询耗时 |
| `agent.action.reconciliation.manual_reviews` | Counter | `actionType`, `outcome` | 统计自动进入人工复核和受权人工重新开启对账 |
| `agent.action.reconciliation.recoveries` | Counter | 无 | 统计发布或消费租约被自动接管的数量 |
| `agent.action.reconciliation.backlog` | Gauge | `resource`, `status` | 每 15 秒读取一次可靠命令、Outbox 和 Inbox 的持久化积压快照 |
| `agent.action.reconciliation.backlog.refresh.failures` | Counter | 无 | 统计积压快照读取失败次数；发生时上一轮 Gauge 可能已过期 |

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
GET /actuator/metrics/agent.action.reconciliation.backlog
GET /actuator/prometheus
```

`/actuator/prometheus` 由 `micrometer-registry-prometheus` 提供，实际生产环境必须仅允许监控系统访问。可直接部署的 Prometheus 规则位于 [action-reliability-alerts.yaml](action-reliability-alerts.yaml)。

对账积压 Gauge 的固定维度如下：

- `resource=outbox`：`PENDING`、`PUBLISHING`
- `resource=inbox`：`RETRY_WAIT`、`FAILED`
- `resource=command`：`UNKNOWN`、`MANUAL_REVIEW`

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
- Outbox 持续积压、对账查询异常、消息契约拒绝和人工复核进入量均可由 Prometheus 规则发现
- 关联日志通过 `reliableEventId` 和 `agentActionId` 连接发布、消费、对账和人工审计；它们绝不作为指标标签

自动化测试覆盖上述指标、结果重放和过期事务边界。部署环境仍应使用真实 MySQL、MCP Server、票务、订单及支付服务各执行一次购票、取消和退票，并核对状态接口、业务订单和指标增量一致。
