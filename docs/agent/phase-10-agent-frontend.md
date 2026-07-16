# 阶段十：智能购票前端闭环

## 1. 阶段目标

本阶段在现有 `console-vue` 中增加智能购票页面，保持项目原有的 Vue 3、Ant Design Vue、局部 `reactive` 状态和 Gateway 请求方式，不引入新的状态管理或 UI 框架。

当前前端已经支持：

1. 创建当前登录用户的智能体会话。
2. 通过 POST SSE 接收模型流式回答。
3. 展示用户消息、助手消息、失败分类和停止生成按钮。
4. 接收购票、取消订单和退票的结构化确认卡片。
5. 提交一次性确认令牌和稳定幂等键。
6. 查询并轮询操作执行状态。
7. 展示购票车票结果、订单号和退款金额。
8. 对 `UNKNOWN` 状态禁止盲目重试，并引导用户查询本人订单。

## 2. 页面入口

新增登录后路由：

```text
/agent
```

侧边栏“车票管理”下新增“智能购票”菜单。

页面继续复用现有 Header、Sider、Breadcrumb 和 Content 布局，智能体页面内部使用左侧当前会话区和右侧聊天区。

## 3. 目录结构

```text
console-vue/src
├── service
│   ├── index.js
│   └── agent-stream.js
└── views
    └── ticket-agent
        ├── index.vue
        └── components
            ├── conversation-panel
            ├── message-item
            ├── chat-input
            └── action-confirm-card
```

组件职责：

- `conversation-panel`：新建会话和当前会话提示。
- `message-item`：用户、助手和错误消息。
- `chat-input`：输入、发送、停止生成和敏感信息提示。
- `action-confirm-card`：操作摘要、倒计时、确认、状态刷新和脱敏结果。

## 4. POST SSE

浏览器原生 `EventSource` 不支持 POST 请求体和自定义认证头，因此本阶段使用：

```text
fetch + ReadableStream + TextDecoder + AbortController
```

请求携带：

- `Authorization`
- `X-Request-Id`
- `Idempotency-Key`
- JSON 对话请求体

前端处理以下事件：

- `meta`
- `delta`
- `action_required`
- `done`
- `error`

`TextDecoder` 使用流式模式，避免中文字符被网络分片截断。

## 5. 高风险操作确认

确认令牌只保存在当前页面内存中：

- 不显示。
- 不写入 URL。
- 不写入日志。
- 不保存到 `localStorage` 或 `sessionStorage`。

确认请求首次生成请求标识后会保存在当前操作对象中。确认响应丢失时，前端先查询持久化状态；用户再次获取结果时继续复用原请求标识，不自动更换幂等键。

状态处理：

| 状态 | 前端行为 |
|---|---|
| `AWAITING_CONFIRMATION` | 显示确认摘要、倒计时和确认按钮 |
| `EXECUTING` | 有限次数轮询状态 |
| `SUCCEEDED` | 展示订单号和脱敏结果 |
| `UNKNOWN` | 禁止盲目重试，引导查询本人订单 |
| `EXPIRED` | 禁用确认并提示重新生成草案 |
| `FAILED` | 展示安全失败状态 |
| `CANCELLED` | 展示操作已经取消 |

## 6. 当前会话保存

当前会话标识按用户保存在 `sessionStorage` 中，关闭浏览器会话后自动清除。

确认令牌、消息正文和操作结果不会写入浏览器持久化存储。

当前后端尚未提供会话列表、历史消息和待确认操作恢复接口，因此本阶段不能在刷新页面后恢复完整消息历史。

后续需要补充：

```text
GET /api/agent-service/conversations
GET /api/agent-service/conversations/{conversationId}/messages
GET /api/agent-service/conversations/{conversationId}/pending-action
```

## 7. 验证

执行：

```text
npm run build
```

生产构建通过。当前警告来自项目既有的 vendor 包体积、Browserslist 数据过期和旧式 `::v-deep` 写法，不影响本阶段页面编译。

## 8. 下一阶段

下一阶段补充后端会话查询接口和前端历史会话恢复，然后进入真实环境联调：

1. 会话列表和历史消息加载。
2. 页面刷新后恢复待确认操作。
3. 会话切换时中止旧 SSE 和轮询任务。
4. 真实模型、MCP、票务、订单和支付服务端到端验证。
