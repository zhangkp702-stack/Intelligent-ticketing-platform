<template>
  <div class="message-row" :class="message.role.toLowerCase()">
    <Avatar
      v-if="message.role === 'ASSISTANT'"
      class="message-avatar assistant-avatar"
    >
      智
    </Avatar>
    <div class="message-content">
      <div class="message-name">
        {{ message.role === 'USER' ? '我' : '智能购票助手' }}
      </div>
      <div class="message-bubble" :class="{ error: message.error }">
        <span v-if="message.content">{{ message.content }}</span>
        <span v-else-if="message.error" class="error-text">
          {{ message.error }}
        </span>
        <span v-else-if="message.pending" class="typing-indicator">
          <LoadingOutlined spin />
          <span>正在思考，请稍候</span>
        </span>
        <span v-else>未返回有效内容</span>
      </div>
      <div
        v-if="
          message.role === 'ASSISTANT' &&
          typeof message.elapsedSeconds === 'number'
        "
        class="message-duration"
      >
        <LoadingOutlined v-if="message.pending" spin />
        <span v-if="message.pending">
          {{ message.content ? '正在生成' : '正在思考' }}，已等待
          {{ message.elapsedSeconds }} 秒
        </span>
        <span v-else>本次耗时 {{ message.elapsedSeconds }} 秒</span>
      </div>
      <details v-if="message.performance" class="performance-details">
        <summary>查看本次请求性能明细</summary>
        <div class="performance-grid">
          <div
            v-for="item in performanceItems(message.performance)"
            :key="item.label"
            class="performance-item"
          >
            <span class="performance-label">{{ item.label }}</span>
            <span class="performance-value">{{ item.value }}</span>
          </div>
        </div>
        <div
          v-if="message.performance.modelCalls?.length"
          class="model-rounds"
        >
          <div class="model-rounds-title">
            回答模型分轮调用（{{ message.performance.modelCalls.length }} 轮）
          </div>
          <div
            v-for="call in message.performance.modelCalls"
            :key="`${call.round}-${call.providerId}-${call.candidateId}`"
            class="model-round"
          >
            <div class="model-round-heading">
              <span>第 {{ call.round }} 轮</span>
              <span :class="['model-outcome', modelOutcomeClass(call.outcome)]">
                {{ formatModelOutcome(call.outcome) }}
              </span>
            </div>
            <div class="model-round-grid">
              <span>模型平台</span>
              <strong>{{ call.providerId || '-' }}</strong>
              <span>候选模型</span>
              <strong>{{ call.candidateId || '-' }}</strong>
              <span>模型名称</span>
              <strong>{{ call.modelId || '-' }}</strong>
              <span>首包耗时</span>
              <strong>{{ formatFirstChunk(call.firstChunkMillis) }}</strong>
              <span>完整耗时</span>
              <strong>{{ formatDuration(call.durationMillis) }}</strong>
              <span>HTTP 状态</span>
              <strong>{{ call.httpStatus >= 0 ? call.httpStatus : '未返回' }}</strong>
            </div>
          </div>
        </div>
        <div class="performance-note">
          该明细仅用于本次实时请求诊断，刷新后不会从历史记录恢复。
        </div>
      </details>
      <Alert
        v-if="message.error && message.content"
        class="message-error"
        type="error"
        show-icon
        :message="message.error"
        :description="message.failureCategory || undefined"
      />
    </div>
    <Avatar v-if="message.role === 'USER'" class="message-avatar user-avatar">
      我
    </Avatar>
  </div>
</template>

<script setup>
import { Alert, Avatar } from 'ant-design-vue'
import { LoadingOutlined } from '@ant-design/icons-vue'

defineProps({
  message: {
    type: Object,
    required: true
  }
})

/**
 * 把后端毫秒耗时格式化为便于快速比较的文本。
 *
 * @param {number} durationMs 毫秒耗时
 * @returns {string} 格式化后的耗时
 */
const formatDuration = (durationMs) => {
  const value = Number(durationMs)
  if (!Number.isFinite(value)) {
    return '-'
  }
  if (value < 1000) {
    return `${value} 毫秒`
  }
  return `${(value / 1000).toFixed(2)} 秒`
}

/**
 * 把后端分流枚举转换为中文展示名称。
 *
 * @param {string} route 后端分流枚举
 * @returns {string} 中文分流名称
 */
const formatRoute = (route) => {
  const labels = {
    CHAT_ONLY: '普通问答',
    TOOL_ASSISTED: 'MCP 工具辅助'
  }
  return labels[route] || route || '-'
}

/**
 * 把工具可用状态转换为中文展示名称。
 *
 * @param {string} availability 后端工具可用状态
 * @returns {string} 中文工具状态
 */
const formatToolAvailability = (availability) => {
  const labels = {
    NOT_REQUIRED: '无需工具',
    AVAILABLE: '工具可用',
    MISSING: '存在缺失工具'
  }
  return labels[availability] || availability || '-'
}

/**
 * 把业务工具组转换为中文名称。
 *
 * @param {string} group 后端业务工具组枚举
 * @returns {string} 中文业务工具组名称
 */
const formatBusinessGroup = (group) => {
  const labels = {
    TRAIN_QUERY: '车次余票查询',
    TRAIN_STOP: '列车经停查询',
    PASSENGER: '乘车人查询',
    ORDER_QUERY: '订单查询',
    PAYMENT: '支付状态查询',
    PURCHASE: '购票草案',
    CANCELLATION: '取消订单草案',
    REFUND: '退票草案'
  }
  return labels[group] || group
}

/**
 * 把 MCP 方法名转换为中文用途，同时保留原方法名方便定位日志。
 *
 * @param {string} tool 后端 MCP 工具名称
 * @returns {string} 中文工具用途和原方法名
 */
const formatTool = (tool) => {
  const labels = {
    resolve_station: '解析车站',
    query_tickets: '查询余票',
    query_train_stops: '查询经停站',
    list_my_passengers: '查询乘车人',
    list_my_orders: '查询本人订单',
    get_my_order_detail: '查询订单详情',
    preview_order_cancellation: '预览取消订单',
    preview_ticket_refund: '预览退票',
    query_pay_status: '查询支付状态',
    prepare_ticket_purchase: '生成购票草案',
    prepare_order_cancellation: '生成取消订单草案',
    prepare_ticket_refund: '生成退票草案'
  }
  return labels[tool] ? `${labels[tool]}（${tool}）` : tool
}

/**
 * 格式化模型首包耗时，区分尚未收到响应的失败调用。
 *
 * @param {number} firstChunkMillis 首包耗时
 * @returns {string} 首包耗时或未返回提示
 */
const formatFirstChunk = (firstChunkMillis) =>
  Number(firstChunkMillis) >= 0
    ? formatDuration(firstChunkMillis)
    : '未返回首包'

/**
 * 把模型 HTTP 调用结果转换为中文。
 *
 * @param {string} outcome 后端调用结果
 * @returns {string} 中文调用结果
 */
const formatModelOutcome = (outcome) => {
  const labels = {
    SUCCESS: '成功',
    STREAM_ENDED: '流正常结束',
    HTTP_ERROR: 'HTTP 错误',
    ERROR: '调用异常',
    CANCELLED: '已取消'
  }
  return labels[outcome] || outcome || '未知'
}

/**
 * 根据模型调用结果返回低基数样式名称。
 *
 * @param {string} outcome 后端调用结果
 * @returns {string} 成功、警告或失败样式
 */
const modelOutcomeClass = (outcome) => {
  if (outcome === 'SUCCESS' || outcome === 'STREAM_ENDED') {
    return 'success'
  }
  if (outcome === 'CANCELLED') {
    return 'warning'
  }
  return 'error'
}

/**
 * 组装性能面板展示项，保持模板只负责渲染。
 *
 * @param {object} performance 后端单次请求性能快照
 * @returns {Array<{label: string, value: string}>} 中文性能展示项
 */
const performanceItems = (performance) => {
  // 阶段耗时与分流结果放在同一面板，便于判断等待发生在哪一步。
  return [
    { label: '后端总耗时', value: formatDuration(performance.totalDurationMs) },
    { label: '上下文加载', value: formatDuration(performance.contextDurationMs) },
    { label: '问题改写', value: formatDuration(performance.rewriteDurationMs) },
    { label: '业务分流', value: formatDuration(performance.routingDurationMs) },
    { label: '模型生成', value: formatDuration(performance.modelDurationMs) },
    { label: '完成与持久化', value: formatDuration(performance.completionDurationMs) },
    {
      label: '改写模型',
      value: performance.rewriteModelInvoked
        ? performance.rewritten
          ? '已调用并改写'
          : '已调用，未改写'
        : '未调用'
    },
    { label: '问答路径', value: formatRoute(performance.route) },
    {
      label: '业务工具组',
      value: performance.matchedGroups?.length
        ? performance.matchedGroups.map(formatBusinessGroup).join('、')
        : '无'
    },
    {
      label: '工具状态',
      value: formatToolAvailability(performance.toolAvailability)
    },
    {
      label: '启用工具',
      value: performance.enabledTools?.length
        ? performance.enabledTools.map(formatTool).join('、')
        : '无'
    },
    {
      label: '缺失工具',
      value: performance.missingTools?.length
        ? performance.missingTools.map(formatTool).join('、')
        : '无'
    }
  ]
}
</script>

<style lang="scss" scoped>
.message-row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 22px;

  &.user {
    justify-content: flex-end;
  }
}

.message-avatar {
  flex: 0 0 auto;
}

.assistant-avatar {
  background: #1e71bd;
}

.user-avatar {
  background: #64a0f6;
}

.message-content {
  max-width: 75%;
}

.user .message-content {
  text-align: right;
}

.message-name {
  margin-bottom: 5px;
  color: #8f9598;
  font-size: 12px;
}

.message-bubble {
  padding: 12px 16px;
  color: #333;
  line-height: 1.8;
  text-align: left;
  white-space: pre-wrap;
  word-break: break-word;
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 4px;

  &.error {
    border-color: #ffccc7;
  }
}

.user .message-bubble {
  color: #fff;
  background: #1e71bd;
  border-color: #1e71bd;
}

.typing-indicator {
  display: inline-flex;
  gap: 8px;
  align-items: center;
  min-width: 128px;
  color: #666;
}

.error-text {
  color: #cf1322;
}

.message-duration {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-top: 6px;
  color: #8f9598;
  font-size: 12px;
}

.message-error {
  margin-top: 8px;
  text-align: left;
}

.performance-details {
  margin-top: 8px;
  padding: 8px 10px;
  color: #595959;
  text-align: left;
  background: #fafafa;
  border: 1px solid #e8e8e8;
  border-radius: 4px;

  summary {
    color: #1e71bd;
    font-size: 12px;
    cursor: pointer;
    user-select: none;
  }
}

.performance-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 16px;
  margin-top: 10px;
}

.performance-item {
  display: flex;
  gap: 8px;
  justify-content: space-between;
  min-width: 0;
  padding-bottom: 5px;
  font-size: 12px;
  border-bottom: 1px dashed #e8e8e8;
}

.performance-label {
  flex: 0 0 auto;
  color: #8c8c8c;
}

.performance-value {
  overflow-wrap: anywhere;
  color: #262626;
  text-align: right;
}

.performance-note {
  margin-top: 10px;
  color: #bfbfbf;
  font-size: 11px;
}

.model-rounds {
  margin-top: 14px;
}

.model-rounds-title {
  margin-bottom: 8px;
  color: #262626;
  font-size: 12px;
  font-weight: 600;
}

.model-round {
  margin-bottom: 8px;
  padding: 8px;
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 3px;
}

.model-round-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 7px;
  color: #262626;
  font-size: 12px;
  font-weight: 600;
}

.model-outcome {
  font-weight: 400;

  &.success {
    color: #389e0d;
  }

  &.warning {
    color: #d48806;
  }

  &.error {
    color: #cf1322;
  }
}

.model-round-grid {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto minmax(0, 1fr);
  gap: 5px 10px;
  font-size: 11px;

  span {
    color: #8c8c8c;
  }

  strong {
    overflow-wrap: anywhere;
    color: #595959;
    font-weight: 400;
    text-align: right;
  }
}

@media (max-width: 900px) {
  .performance-grid {
    grid-template-columns: 1fr;
  }

  .model-round-grid {
    grid-template-columns: auto minmax(0, 1fr);
  }
}

</style>
