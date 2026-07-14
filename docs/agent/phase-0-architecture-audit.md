# 购票智能体阶段 0：现状审计与架构决策

## 1. 文档状态

- 阶段：阶段 0
- 结论：审计完成，可以进入阶段 1
- 本阶段代码变更：无
- 本阶段业务配置变更：无
- 本文档目的：固化后续实现边界，避免把 Spring AI、模型调用和智能体状态直接耦合进现有业务服务

## 2. 仓库现状

### 2.1 仓库边界

真实 Git 仓库根目录为：

```text
E:\JavaCode\origin12306\origin12306
```

外层目录保存 `AGENTS.md` 等开发约束，不是 Maven 工程根目录。

### 2.2 当前技术栈

| 项目 | 当前版本或实现 |
|---|---|
| Java | 17 |
| Maven Wrapper | 3.9.2 |
| Spring Boot | 3.0.7 |
| Spring Cloud | 2022.0.3 |
| Spring Cloud Alibaba | 2022.0.0.0-RC2 |
| MyBatis-Plus | 3.5.3.1 |
| ShardingSphere | 5.3.2 |
| Redis Client | Redisson 3.21.3 |
| 服务发现 | Nacos |
| 消息队列 | RocketMQ |
| 网关 | Spring Cloud Gateway |

现有服务端口：

| 服务 | 端口 |
|---|---:|
| gateway-service | 9000 |
| user-service | 9001 |
| ticket-service | 9002 |
| order-service | 9003 |
| pay-service | 9004 |

### 2.3 工作区保护边界

当前工作区存在未提交修改，主要涉及：

- 网关限流。
- 验证码和风险校验。
- 票务接口限流。
- 座位位图占用和释放。
- 本地余票缓存。
- Maven 配置调整。

这些修改均视为用户已有工作。后续阶段不得重置、覆盖或顺带重构，所有智能体改动必须与其隔离。

## 3. 架构决策

### ADR-001：智能体独立于 ticket-service

决定新增顶层聚合模块：

```text
ai-services
├── pom.xml
├── ticket-agent-service
└── ticket-mcp-server
```

职责划分：

- `ticket-agent-service`：会话、主题、摘要、上下文装配、模型路由、对话编排、操作草稿和确认。
- `ticket-mcp-server`：把现有用户、票务、订单和支付接口适配成标准 MCP 工具。
- 原有服务继续负责真实业务状态，不复制出票、占座、订单或退款核心逻辑。

原因：

- 现有业务服务使用 Spring Boot 3.0.7，直接加入当前 Spring AI 会造成依赖版本冲突。
- 智能体需要独立扩缩容、限流、模型降级和故障隔离。
- 模型服务不可用时不能影响原有购票系统。

### ADR-002：AI 服务使用独立依赖版本线

阶段 1 采用以下基线：

| 依赖 | 计划版本 |
|---|---|
| Java | 17 |
| Spring Boot | 3.5.x，阶段 1 通过有效 POM 固定补丁版本 |
| Spring AI | 1.1.8 |
| Spring Cloud | 2025.0.x |
| Spring Cloud Alibaba | 2025.0.0.0 |

`ai-services` 参加根 Maven Reactor 构建，但使用自己的依赖管理，不继承现有 `index12306-dependencies` 中的 Spring Boot 3.0.7 版本。

暂不采用 Spring Boot 4 + Spring AI 2.0，原因是：

- 当前业务项目仍处于 Boot 3.0.7 版本线。
- Spring AI 2.0 和 MCP Java SDK 2.0 包含破坏性升级。
- 第一阶段目标是稳定接入，而不是同时验证新的主版本生态。
- Spring AI 1.1.8 已满足 OpenAI 兼容模型、工具调用和 Streamable HTTP MCP 的需要。

未来升级 Spring AI 2.0 时只升级 `ai-services`，不要求同步升级现有业务服务。

### ADR-003：使用 Stateless Streamable HTTP MCP

采用 Spring AI MCP Client/Server，传输方式为内部网络上的 Stateless Streamable HTTP：

```text
ticket-agent-service --MCP--> ticket-mcp-server --HTTP/Feign--> existing services
```

第一版只使用 MCP Tools，不开放 MCP Resources、Prompts、Sampling 和 Elicitation。理由是工具调用是当前唯一必需能力，无状态传输更适合微服务部署，也避免在 MCP Server 中保存对话状态。

MCP Server 不对公网和浏览器直接暴露，仅允许 `ticket-agent-service` 通过内部服务身份访问。

### ADR-004：模型只持有只读工具

工具分为两类：

- `READ`：车站、车次、余票、乘车人、订单、支付状态查询，可以注册给模型。
- `WRITE`：购票、取消、退票、支付创建，不注册到模型的自动工具集合。

写操作固定流程：

```text
模型生成业务草稿
→ 后端校验并重新查询实时状态
→ 用户查看确认卡片
→ 用户显式确认
→ 后端校验确认令牌和草稿版本
→ 程序调用指定 MCP 写工具
```

模型不得自行调用写工具，也不得生成可绕过确认接口的参数。

### ADR-005：按角色进行模型路由和降级

模型角色至少包括：

- `ANSWER_TOOL`：最终回答、参数收集和只读工具规划。
- `TOPIC_ROUTE`：主题识别。
- `MEMORY_SUMMARY`：异步摘要压缩。
- `PARAMETER_REPAIR`：工具参数格式修复，默认先由主回答模型承担。

候选模型：

```text
ANSWER_TOOL
1. 百炼 qwen3.6-plus 固定快照
2. SiliconFlow Qwen/Qwen3.6-35B-A3B
3. SiliconFlow Qwen/Qwen3-32B
4. 百炼 qwen3.6-flash

TOPIC_ROUTE / MEMORY_SUMMARY
1. SiliconFlow Qwen/Qwen3.5-9B
2. 百炼 qwen3.5-flash
3. 百炼 qwen3.6-flash
```

SiliconFlow 模型 ID 在阶段 2 通过 `/models` 和真实能力测试确认，未经工具调用认证的模型不得进入 `ANSWER_TOOL` 工具链。

参考 Ragent 的以下机制：

- 配置化 Provider 和候选模型。
- 默认模型优先，其余模型按优先级排序。
- CLOSED、OPEN、HALF_OPEN 熔断状态。
- 连续失败阈值和熔断恢复时间。
- 流式响应在首包成功前切换模型。

需要在 Ragent 方案上增加：

- 按模型角色分组，而不是只有通用 chat 组。
- `provider + model + role` 级别的健康状态。
- 区分可降级的模型异常和不可重试的业务异常。
- 工具调用、结构化输出、流式输出、上下文长度等能力矩阵。
- 单次尝试超时、请求总时间预算和平台并发隔离。
- 模型调用审计与 Token、延迟、降级原因指标。

### ADR-006：主题化记忆和异步压缩

主题识别输入：

```text
当前用户问题
+ 最近几条用户问题
+ 多个主题短摘要卡片
+ 活跃任务
+ 最近待确认操作
```

主题确定后加载：

```text
该主题完整摘要
+ 结构化业务状态
+ 最近 2～4 轮完整对话
+ 最近工具结果
+ 当前操作草稿
+ 待补充参数
```

摘要由独立低成本模型异步执行。摘要失败不阻塞用户回答，不删除原始消息；摘要更新使用消息范围、摘要版本和乐观锁避免并发覆盖。

异步任务不得依赖隐式 ThreadLocal 传播。对话入口生成不可变 `AgentRequestContext`，显式携带请求 ID、会话 ID、主题 ID、用户 ID、用户名和认证信息。

### ADR-007：智能体使用独立数据库

使用非分片数据库：

```text
12306_agent
```

计划表：

| 表 | 用途 |
|---|---|
| `t_agent_conversation` | 会话 |
| `t_agent_topic_task` | 会话内主题任务 |
| `t_agent_message` | 原始消息 |
| `t_agent_turn` | 完整问答轮次 |
| `t_agent_memory_summary` | 主题摘要版本 |
| `t_agent_context_snapshot` | 模型上下文快照元数据 |
| `t_agent_model_call` | 模型调用和降级审计 |
| `t_agent_tool_call` | MCP 工具调用审计 |
| `t_agent_action_draft` | 购票、取消和退票草稿 |
| `t_agent_action_execution` | 写操作执行状态 |
| `t_agent_execution_event` | 执行和补偿事件 |

阶段 3 再确定字段、索引、唯一约束和状态机，本阶段不创建 SQL。

## 4. 现有业务 API 与 MCP 映射

### 4.1 可直接适配的只读能力

| MCP 工具候选 | 现有接口 | 备注 |
|---|---|---|
| `resolve_station` | `GET /api/ticket-service/region-station/query` | 将城市或站名解析为业务站点编码 |
| `list_stations` | `GET /api/ticket-service/station/all` | 结果较大，只用于缓存或精确匹配兜底 |
| `query_tickets` | `GET /api/ticket-service/ticket/query` | 输入包含出发地、目的地和出发日期 |
| `query_train_stops` | `GET /api/ticket-service/train-station/query` | 根据 trainId 查询经停站 |
| `list_my_passengers` | `GET /api/user-service/passenger/query` | 依赖可信 UserContext |
| `list_my_orders` | `GET /api/order-service/order/ticket/self/page` | 当前返回字段不足，需要补充订单号和状态 |
| `query_pay_status` | `GET /api/ticket-service/ticket/pay/query` | 必须增加订单归属校验 |

### 4.2 写能力候选

| MCP 工具候选 | 现有接口 | 当前结论 |
|---|---|---|
| `purchase_ticket` | `POST /api/ticket-service/ticket/purchase/v2` | 使用 V2；必须由确认执行器调用 |
| `cancel_ticket_order` | `POST /api/ticket-service/ticket/cancel` | 必须增加订单归属校验和幂等语义 |
| `refund_ticket` | `POST /api/ticket-service/ticket/refund` | 当前实现不完整，不能直接开放 |
| `create_payment` | `POST /api/pay-service/pay/create` | 不纳入第一版基本闭环，后续按支付跳转场景处理 |

### 4.3 缺失能力

后续需要补充或适配：

- 按当前登录用户安全查询订单详情。
- 返回订单号、订单状态和可操作项的本人订单列表。
- 购票执行前的实时草稿校验或报价接口。
- 取消订单预检查。
- 退票前的可退项目和退款金额预览。
- 可追踪的退票结果 DTO。
- 写操作执行后的结果反查。

## 5. 已发现的关键风险和阻塞项

### 5.1 身份和权限

1. 网关仅对配置在 `blackPathPre` 中的路径校验 Token 并注入用户头，命名与实际语义相反，容易误配。
2. 支付服务路由当前没有统一的 Token 校验过滤器。
3. `queryTicketOrderByOrderSn` 只按订单号查询，没有校验订单是否属于当前用户。
4. 订单取消逻辑只校验订单状态，没有校验当前用户归属。
5. 现有业务服务通过请求头构建 `UserContext`，内部服务如果可以被外部直连，用户头可能被伪造。

因此，MCP Server 必须同时校验：

- 调用方是受信任的 `ticket-agent-service`。
- 终端用户身份来自网关验证结果，而不是模型参数。
- 被操作订单属于该终端用户。

### 5.2 退票流程不完整

当前 `commonTicketRefund` 存在以下问题：

- 最终返回 `null`。
- `RefundTicketRespDTO` 没有字段。
- 部分退票金额计算使用了全部乘客明细，存在金额范围错误风险。
- 缺少独立的退票预览接口。
- 缺少明确的可重试、处理中和最终失败状态。

阶段 8 前必须先修复和补齐，不能把当前退票接口直接暴露为智能体写工具。

### 5.3 订单查询契约不足

- 本人订单分页 DTO 缺少智能体后续操作所需的订单号和状态信息。
- 订单号查询接口未处理订单不存在时的安全返回和用户归属。
- 智能体不能仅凭模型记住的订单号进行取消或退票。

### 5.4 配置安全

现有配置文件中存在数据库、Redis、Nacos、支付等敏感配置明文。智能体阶段不得继续复制这种做法：

- 百炼和 SiliconFlow API Key 必须只从环境变量或密钥服务读取。
- MCP 内部认证密钥不得提交到 Git。
- 上线前应安排现有支付密钥迁移和轮换；本阶段只记录风险，不修改用户现有配置。

### 5.5 编码和工作区风险

- 多个现有 Java 和 YAML 文件显示中文乱码。
- Maven 运行时平台编码为 GBK，而项目声明源文件编码为 UTF-8。
- 后续只使用 ASCII 稳定上下文进行 Java 补丁定位。
- 每次编辑后必须检查 `git diff`，防止换行符、BOM 或整文件编码变化。

## 6. 外部接口和认证边界

### 6.1 浏览器到智能体服务

```text
Browser
→ Gateway：校验用户 Token
→ ticket-agent-service：建立 AgentRequestContext
```

所有 `/api/agent-service/**` 接口都必须要求登录，不采用部分路径前缀保护。

### 6.2 智能体到 MCP Server

```text
ticket-agent-service
→ 内部服务凭证
→ 用户身份声明
→ ticket-mcp-server
```

内部服务凭证与用户身份分离：

- 服务凭证证明请求来自 Agent Service。
- 用户身份声明决定数据权限。
- 模型生成内容不能覆盖这两类信息。

### 6.3 MCP Server 到业务服务

MCP Server 通过受控客户端调用业务服务，统一注入经过验证的用户上下文。写工具在调用前必须再次校验草稿、用户归属、状态和幂等键。

## 7. 阶段 1 修改边界

阶段 1 只允许进行以下修改：

1. 根 `pom.xml` 增加 `ai-services` 聚合模块。
2. 新增 `ai-services/pom.xml`。
3. 新增 `ticket-agent-service` 最小启动模块。
4. 新增 `ticket-mcp-server` 最小启动模块。
5. 加入健康检查和基础配置占位符。
6. 配置独立 Spring Boot/Spring AI BOM，但暂不配置真实 API Key。
7. 添加最小启动和上下文加载测试。

阶段 1 不允许：

- 修改 `ticket-service`、`order-service`、`pay-service` 或 `user-service` 业务逻辑。
- 创建智能体数据表。
- 调用真实模型。
- 实现 MCP 业务工具。
- 修改前端。
- 修改当前限流、验证码和座位位图代码。

## 8. 阶段 1 验收标准

- 原项目模块仍能按原依赖版本构建。
- 两个 AI 服务使用独立 Boot 3.5.x 依赖线。
- Maven 有效 POM 中不存在 Boot 3.0.7 与 3.5.x 混用。
- 两个 AI 服务可分别启动并通过健康检查。
- 所有敏感配置均为环境变量占位符。
- Git diff 不包含现有业务 Java 文件的编码或换行变化。
- 本阶段发现的用户已有修改保持不变。

## 9. 阶段 0 验收结论

阶段 0 已完成以下目标：

- 确认真实仓库和未提交修改边界。
- 确认现有依赖版本和运行环境。
- 确认查询、购票、订单、支付和退票接口入口。
- 确认 Ragent 降级机制的可复用部分与缺失能力。
- 确认独立 AI 服务、独立数据库和 MCP 安全边界。
- 识别订单归属、退票实现、配置密钥和编码风险。
- 固化阶段 1 的修改范围和验收标准。

结论：可以进入阶段 1，但写操作 MCP 必须等待订单归属校验和退票流程补齐后再开放。

## 10. 官方参考资料

- Spring AI 1.x 支持的 Spring Boot 版本：<https://docs.spring.io/spring-ai/reference/1.0/getting-started.html>
- Spring AI 1.1 MCP Server：<https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-server-boot-starter-docs.html>
- Spring AI MCP Client：<https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html>
- Spring Cloud Alibaba 版本关系：<https://sca.aliyun.com/en/docs/2025.x/overview/version-explain/>
- 百炼 Function Calling：<https://help.aliyun.com/en/model-studio/qwen-function-calling>
- SiliconFlow Chat Completions：<https://docs.siliconflow.com/en/api-reference/chat-completions/chat-completions>
- SiliconFlow 模型列表接口：<https://docs.siliconflow.com/en/api-reference/models/get-model-list>
