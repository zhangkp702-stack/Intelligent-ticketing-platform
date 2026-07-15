# 阶段四：主题路由与异步记忆压缩

## 1. 阶段目标

阶段四把阶段二的多模型路由与阶段三的记忆表连接起来，形成两条可运行链路：

1. 使用 `TOPIC_ROUTE` 判断当前问题属于已有主题还是新主题；
2. 使用 `MEMORY_SUMMARY` 在回答事务提交后异步生成主题累积摘要。

本阶段不实现聊天 Controller、SSE、MCP 票务工具、购票确认、退票确认和订单执行。这些能力仍属于后续阶段。

## 2. 上下文传递原则

- 同步请求使用不可变 `AgentRequestContext` 显式传递 `requestId`、用户、会话、轮次和主题标识。
- 不使用普通 `ThreadLocal` 保存身份或业务状态。
- 不使用 Alibaba TTL 在线程池间复制业务上下文。
- 摘要异步线程只接收 `taskId`，通过数据库恢复会话、主题、旧摘要和冻结消息范围。
- 如后续需要日志链路，可单独为 MDC 使用上下文装饰器，但它不能成为鉴权或业务数据来源。

## 3. 主题路由流程

1. 按 `requestId` 查询 `t_agent_context_route_log`，已有结果直接幂等复用。
2. 加载当前问题、最近用户问题和最近主题摘要卡片；历史助手回答不会进入主题判断输入。
3. 没有候选主题时确定性创建首个主题，不调用模型。
4. 有候选主题时通过 `TOPIC_ROUTE` 多模型降级链获取 JSON 结果。
5. 在单个候选模型尝试内部完成 JSON、决策、置信度和候选主题范围校验；非法结果会切换下一模型。
6. 置信度低于 `topic-route-confidence-threshold` 时，优先沿用仍在候选集合中的活动主题，否则创建新主题。
7. 绑定轮次与主题，写入路由日志，并加载完整主题摘要、未压缩消息和上下文快照。

模型只能返回 `SELECT_EXISTING` 或 `CREATE_NEW`。`FALLBACK_ACTIVE` 只能由后端确定性规则产生。

## 4. 异步摘要流程

1. `ConversationMemoryService.completeTurn` 在回答、轮次和主题状态同一事务中更新完成后发布 `TurnCompletedEvent`。
2. `SummaryTriggerListener` 仅在事务成功提交后检查摘要阈值。
3. 达到阈值时幂等创建 `t_agent_summary_task`，将任务标识交给 `agentSummaryExecutor`。
4. 异步处理器领取任务并从数据库恢复上一版完整摘要、结构化状态和冻结消息范围。
5. `ModelSummaryTaskProcessor` 调用 `MEMORY_SUMMARY` 降级链，要求返回完整累积摘要、短摘要和 JSON 对象状态。
6. 成功时原子写入新摘要、替代旧版本、推进主题摘要边界并完成任务；失败时进入既有重试状态机。

`summary-auto-dispatch-enabled` 默认开启。测试配置关闭自动调度，以便持久化状态机测试不访问外部模型。

## 5. 模型审计与安全

- 每个成功或失败的候选模型尝试均写入 `t_agent_model_call`。
- 审计只保存请求关联、模型标识、降级位置、结果、耗时和异常类型，不保存提示词、响应正文或 API Key。
- 审计使用独立事务；审计写入失败只记录指标和告警，不反向导致用户模型调用失败。
- 数据库用户名和密码由 `AGENT_DATASOURCE_USERNAME`、`AGENT_DATASOURCE_PASSWORD` 环境变量提供，配置文件不再包含数据库密码明文。

## 6. 关键配置

```yaml
index12306:
  agent:
    memory:
      topic-route-confidence-threshold: 0.55
      summary-trigger-message-count: 12
      summary-auto-dispatch-enabled: true
      summary-executor:
        core-pool-size: 2
        max-pool-size: 4
        queue-capacity: 200
```

## 7. 阶段验收条件

- 首主题不调用模型且可幂等创建；
- 已有主题使用结构化多模型路由，低置信度有确定性兜底；
- 模型格式或语义错误可以触发候选降级；
- 回答事务回滚时不会触发摘要；
- 异步摘要不依赖请求线程上下文；
- 模型尝试可在数据库中按请求、会话、主题和轮次追踪；
- 模块编译及全部自动化测试通过。
