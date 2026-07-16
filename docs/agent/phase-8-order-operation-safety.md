# 阶段八：订单操作安全基线与只读预览

## 1. 阶段目标

本阶段不直接向回答模型开放取消订单和退票写工具，而是先修复原有业务接口的安全边界，并补齐后续确认执行所需的只读能力：

1. 用户可见的订单详情必须校验订单归属。
2. 是否可支付、可取消、可退票由订单服务根据持久化状态和发车时间计算。
3. 取消订单在并发和重复请求下保持幂等。
4. 退票预览与真实退款共用同一组选票和金额计算逻辑。
5. 部分退票只计算本次选中的车票金额。
6. 支付服务使用退款请求标识防止重复调用支付渠道。
7. MCP 只暴露订单详情、取消预检查、退票预览和支付状态查询。

真实取消订单和真实退票的 Agent 确认执行仍属于下一阶段，必须复用阶段七的草案、一次性确认令牌、幂等键和审计状态机。

## 2. 订单归属和可操作状态

新增当前用户订单详情接口：

```http
GET /api/order-service/order/ticket/query/self?orderSn={orderSn}
```

该接口同时匹配订单号和网关注入的当前用户标识。订单不存在、用户上下文缺失或订单属于其他用户时，统一返回“订单不存在或无权访问”，避免通过订单号枚举他人订单。

票务服务增加 Feign 用户上下文拦截器，将当前线程中已经验证的 `userId`、`username` 和 `realName` 请求头透传给下游服务。拦截器不读取订单请求体中的用户标识，也不向下游扩散用户令牌。

订单详情和本人订单分页结果新增：

- `orderSn`
- `status`
- `canCancel`
- `canPay`
- `canRefund`

当前规则：

- 待支付且尚未发车：允许支付、允许取消。
- 已支付且尚未发车：允许退票。
- 已关闭、已取消、已退票或已经发车：不允许继续执行对应操作。

订单子项查询也会先校验订单归属，防止通过订单号和子订单记录标识读取他人乘车信息。

## 3. 取消订单安全

新增只读预检查接口：

```http
GET /api/ticket-service/ticket/cancel/preview?orderSn={orderSn}
```

真实取消流程仍使用原有接口，但执行前增加：

- 当前用户订单归属校验。
- 服务端 `canCancel` 校验。
- 分布式锁内重新读取订单状态。
- 仅允许待支付状态更新为关闭状态。
- 已关闭订单重复取消直接返回成功。
- 下游取消失败时不执行座位和余票回滚。

内部超时关单继续使用不依赖用户上下文的内部流程，避免影响消息队列触发的自动关单。

## 4. 退票预览和金额修复

新增只读预览接口：

```http
POST /api/ticket-service/ticket/refund/preview
Content-Type: application/json

{
  "orderSn": "订单号",
  "type": 0,
  "subOrderRecordIdReqList": ["子订单记录ID"]
}
```

`type` 只允许：

- `0`：部分退款，必须指定子订单记录。
- `1`：全部退款，选择订单内仍处于已支付状态的车票。

预览和执行共用同一个退款计划：

- 先校验订单归属和服务端 `canRefund`。
- 部分退款必须完整命中调用方指定的子订单记录。
- 只保留已支付状态的车票。
- 退款金额只汇总本次选中的车票。
- 真实退款返回请求标识、订单号、退款类型、退款金额、状态和交易号。

当前项目尚未提供退票手续费和时间梯度规则，因此本阶段的“预计退款金额”是所选车票的票面金额。接入正式铁路退票费策略后，应在共享退款计划中统一扣减，不能让模型自行估算手续费。

## 5. 退款幂等

支付库 `12306_pay.t_refund` 新增：

```text
refund_request_id varchar(64)
uk_refund_request_id_card(refund_request_id, id_card)
```

升级已有数据库时执行：

```text
resources_sql_sharded/upgrade/phase-8-refund-idempotency.sql
```

该脚本必须在 `12306_pay` 库执行，不在 Agent 独立库执行。初始化新环境时，`resources_sql_sharded/db/12306-springcloud-pay.sql` 已包含该字段和索引。

幂等规则：

- 调用方提供不超过 64 字符的 `requestId` 时直接使用。
- 未提供时，票务服务根据当前用户、订单号、退款类型和排序后的子订单范围生成稳定标识。
- 支付服务在调用支付渠道前先按请求标识查询已有退款记录。
- 同一订单的支付单通过数据库行锁串行计算剩余可退金额，防止不同请求并发超退。
- 获得行锁后再次检查请求标识，复用等待期间已经完成的相同退款。
- 相同请求标识再次提交时返回已保存结果，不重复调用第三方退款。
- 唯一索引阻止同一乘车人退款记录被并发重复插入。

## 6. MCP 只读工具

MCP 服务新增四个只读工具：

- `get_my_order_detail`
- `preview_order_cancellation`
- `preview_ticket_refund`
- `query_pay_status`

工具层不接收 `userId` 参数，身份只来自 Agent 签名元数据和网关注入的用户上下文。返回订单详情时不向模型暴露证件号。

Agent 回答模型当前可见 9 个只读工具和 1 个阶段七专用购票执行工具。取消订单和退票写操作没有加入回答模型工具白名单。

## 7. 测试开关

根 Maven 配置仍默认跳过旧项目测试，但现在可以通过参数显式开启：

```text
-DskipTests=false
```

阶段八验证命令：

```text
.\mvnw.cmd -pl services/order-service,services/pay-service -am -DskipTests=false -Dtest=OrderServiceImplTests,RefundServiceImplTests -Dsurefire.failIfNoSpecifiedTests=false test
.\mvnw.cmd -pl services/ticket-service -am -DskipTests=false -Dtest=TicketServiceImplTests -Dsurefire.failIfNoSpecifiedTests=false test
.\mvnw.cmd -pl ai-services/ticket-agent-service,ai-services/ticket-mcp-server -am -DskipTests=false test
```

测试覆盖：

- 本人订单详情和服务端可操作状态。
- 猜测他人订单号时拒绝访问。
- 部分退票只计算选中车票金额。
- 票务服务向订单服务透传已验证用户身份。
- 相同退款请求返回已有结果且不重复调用支付渠道。
- MCP 工具数量、签名身份传递和订单详情查询。

## 8. 下一阶段

阶段九为取消订单和退票增加完整的 Agent 写操作闭环：

1. 模型只生成取消或退票草案。
2. 服务端再次执行本阶段的只读预检查。
3. 用户通过一次性令牌显式确认。
4. 专用执行器调用不暴露给回答模型的写工具。
5. 保存幂等键、参数指纹、执行状态和脱敏结果。
6. 对超时或网络中断标记 `UNKNOWN`，先查询业务结果，不自动重复写入。
