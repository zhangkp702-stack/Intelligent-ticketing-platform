<template>
  <div class="agent-page">
    <Row :gutter="16" class="agent-row">
      <Col :span="6" class="agent-column">
        <ConversationPanel
          :conversation-id="state.conversationId"
          :conversations="state.conversations"
          :creating="state.creatingConversation"
          :loading="state.loadingConversations"
          :loading-more="state.loadingMoreConversations"
          :has-more="state.conversations.length < state.conversationTotal"
          @create="createConversation"
          @select="selectConversation"
          @load-more="loadMoreConversations"
        />
      </Col>
      <Col :span="18" class="agent-column">
        <Card :bordered="false" class="chat-card">
          <template #title>
            <div class="chat-title">
              <span>12306 智能购票助手</span>
              <Tag color="blue">Spring AI + MCP</Tag>
            </div>
          </template>
          <div ref="messageContainer" class="message-container">
            <div v-if="state.loadingHistory" class="history-loading">
              <Spin tip="正在恢复会话记录" />
            </div>
            <div v-else-if="!state.messages.length" class="empty-content">
              <Empty description="开始一次智能购票对话">
                <template #image>
                  <RobotOutlined class="empty-robot" />
                </template>
              </Empty>
              <div class="starter-title">你可以这样问：</div>
              <Space wrap>
                <Button
                  v-for="question in starterQuestions"
                  :key="question"
                  :disabled="state.creatingConversation || state.streaming"
                  @click="sendMessage(question)"
                >
                  {{ question }}
                </Button>
              </Space>
            </div>
            <template v-else>
              <div v-if="state.historyHasMore" class="history-more">
                <Button
                  type="link"
                  :loading="state.loadingMoreHistory"
                  @click="loadMoreHistory"
                >
                  加载更早消息
                </Button>
              </div>
              <template
                v-for="chatMessage in state.messages"
                :key="chatMessage.id"
              >
                <MessageItem :message="chatMessage" />
                <ActionConfirmCard
                  v-if="chatMessage.action"
                  :action="chatMessage.action"
                  :execution="chatMessage.actionExecution"
                  :confirming="
                    state.confirmingActionId === chatMessage.action.actionId
                  "
                  :refreshing="
                    state.refreshingActionId === chatMessage.action.actionId
                  "
                  @confirm="confirmAction(chatMessage)"
                  @refresh="refreshAction(chatMessage)"
                  @view-orders="router.push('/ticketList')"
                />
              </template>
            </template>
          </div>
          <ChatInput
            :disabled="
              state.creatingConversation ||
              state.loadingHistory ||
              state.loadingConversations
            "
            :streaming="state.streaming"
            @send="sendMessage"
            @stop="stopStreaming"
          />
        </Card>
      </Col>
    </Row>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  Button,
  Card,
  Col,
  Empty,
  Row,
  Space,
  Spin,
  Tag,
  message
} from 'ant-design-vue'
import { RobotOutlined } from '@ant-design/icons-vue'
import Cookie from 'js-cookie'
import { useRouter } from 'vue-router'
import {
  fetchAgentActionStatus,
  fetchAgentConversationMessages,
  fetchAgentConversations,
  fetchAgentPendingAction,
  fetchConfirmAgentAction,
  fetchCreateAgentConversation
} from '@/service'
import {
  cancelAgentChat,
  createAgentRequestId,
  streamAgentChat
} from '@/service/agent-stream'
import ConversationPanel from './components/conversation-panel'
import MessageItem from './components/message-item'
import ChatInput from './components/chat-input'
import ActionConfirmCard from './components/action-confirm-card'

const router = useRouter()
const messageContainer = ref(null)
const storageKey = `ticket-agent-conversation-${
  Cookie.get('userId') || Cookie.get('username') || 'current'
}`
const terminalStatuses = [
  'SUCCEEDED',
  'UNKNOWN',
  'EXPIRED',
  'FAILED',
  'CANCELLED'
]
const pollTimers = new Map()
let streamController
let streamGeneration = 0
let activeStreamRequest
let conversationLoadGeneration = 0

const state = reactive({
  conversationId: sessionStorage.getItem(storageKey),
  conversations: [],
  conversationCurrent: 1,
  conversationTotal: 0,
  messages: [],
  historyCursor: null,
  historyHasMore: false,
  loadingConversations: false,
  loadingMoreConversations: false,
  loadingHistory: false,
  loadingMoreHistory: false,
  creatingConversation: false,
  streaming: false,
  confirmingActionId: null,
  refreshingActionId: null
})

const starterQuestions = [
  '帮我查询明天北京到上海的高铁',
  '查询我的未支付订单',
  '我想购买一张车票',
  '帮我看看哪些车票可以退'
]

/**
 * 分页加载当前用户的智能体会话。
 *
 * @param {boolean} reset 是否从第一页重新加载
 * @returns {Promise<boolean>} 加载成功时返回 true
 */
const loadConversations = async (reset = false) => {
  const targetPage = reset ? 1 : state.conversationCurrent
  if (reset) {
    state.loadingConversations = true
  } else {
    state.loadingMoreConversations = true
  }
  try {
    const result = await fetchAgentConversations({
      current: targetPage,
      size: 20
    })
    const records = result.records || []

    // 追加分页时按会话标识去重，避免更新时间变化造成跨页重复。
    if (reset) {
      state.conversations = records
    } else {
      const existingIds = new Set(
        state.conversations.map((item) => item.conversationId)
      )
      state.conversations.push(
        ...records.filter((item) => !existingIds.has(item.conversationId))
      )
    }
    state.conversationCurrent = targetPage
    state.conversationTotal = result.total || 0
    return true
  } catch (error) {
    message.error(error.response?.data?.message || '加载历史会话失败')
    return false
  } finally {
    state.loadingConversations = false
    state.loadingMoreConversations = false
  }
}

/**
 * 加载下一页历史会话。
 */
const loadMoreConversations = async () => {
  if (
    state.loadingMoreConversations ||
    state.conversations.length >= state.conversationTotal
  ) {
    return
  }
  state.conversationCurrent += 1
  const loaded = await loadConversations()
  if (!loaded) {
    state.conversationCurrent -= 1
  }
}

/**
 * 把后端历史消息转换为页面消息结构。
 *
 * @param {object} historyMessage 后端历史消息
 * @returns {object} 页面消息
 */
const mapHistoryMessage = (historyMessage) => ({
  id: historyMessage.messageId,
  role: historyMessage.role,
  content: historyMessage.content,
  turnId: historyMessage.turnId,
  topicId: historyMessage.topicId,
  sequenceNo: historyMessage.sequenceNo,
  createdAt: historyMessage.createdAt
})

/**
 * 中止当前会话的流读取和状态轮询，并异步通知后端终止对应生成任务。
 *
 * @returns {object | undefined} 被取消的请求标识信息
 */
const cancelActiveConversationWork = () => {
  const activeRequest = activeStreamRequest
  activeStreamRequest = null
  streamGeneration += 1
  streamController?.abort()
  streamController = null
  state.streaming = false
  state.loadingHistory = false
  state.loadingMoreHistory = false
  state.confirmingActionId = null
  state.refreshingActionId = null
  clearStatusPolls()
  if (activeRequest) {
    // 显式通知后端取消，网关没有立刻感知连接断开时也能终止模型任务。
    cancelAgentChat(activeRequest).catch(() => undefined)
  }
  return activeRequest
}

/**
 * 把恢复出的操作卡片挂到对应助手消息，缺少该轮历史时创建安全占位消息。
 *
 * @param {object | null} recoveredAction 后端恢复结果
 * @returns {object | null} 挂载操作卡片的页面消息
 */
const applyRecoveredAction = (recoveredAction) => {
  if (!recoveredAction?.action) {
    return null
  }
  let actionMessage = state.messages
    .slice()
    .reverse()
    .find(
      (item) =>
        item.role === 'ASSISTANT' && item.turnId === recoveredAction.turnId
    )
  if (!actionMessage) {
    actionMessage = {
      id: `recovered-action-${recoveredAction.action.actionId}`,
      role: 'ASSISTANT',
      content: '该会话包含一项可恢复的票务操作。',
      turnId: recoveredAction.turnId
    }
    state.messages.push(actionMessage)
  }

  // 确认令牌只进入当前内存对象，不写入浏览器持久化存储。
  actionMessage.action = recoveredAction.action
  actionMessage.actionExecution = recoveredAction.execution
  return actionMessage
}

/**
 * 更早历史页包含原操作轮次时，把占位卡片迁移回真实助手消息。
 */
const reconcileRecoveredActionMessage = () => {
  const placeholderIndex = state.messages.findIndex((item) =>
    item.id.startsWith('recovered-action-')
  )
  if (placeholderIndex < 0) {
    return
  }
  const placeholder = state.messages[placeholderIndex]
  const actualMessage = state.messages.find(
    (item) =>
      item.id !== placeholder.id &&
      item.role === 'ASSISTANT' &&
      item.turnId === placeholder.turnId
  )
  if (!actualMessage) {
    return
  }

  // 操作卡片只保留一份，避免加载更早消息后出现重复操作入口。
  actualMessage.action = placeholder.action
  actualMessage.actionExecution = placeholder.actionExecution
  state.messages.splice(placeholderIndex, 1)
}

/**
 * 切换会话并恢复最近消息和操作卡片。
 *
 * @param {string} conversationId 目标会话标识
 * @param {boolean} force 是否强制重新加载当前会话
 */
const selectConversation = async (conversationId, force = false) => {
  if (
    !conversationId ||
    (!force && conversationId === state.conversationId && state.messages.length)
  ) {
    return
  }
  cancelActiveConversationWork()
  const loadGeneration = ++conversationLoadGeneration
  state.conversationId = conversationId
  state.messages = []
  state.historyCursor = null
  state.historyHasMore = false
  state.loadingHistory = true
  sessionStorage.setItem(storageKey, conversationId)

  try {
    // 消息与操作恢复互不依赖，并行读取可缩短页面切换等待时间。
    const [historyResult, actionResult] = await Promise.allSettled([
      fetchAgentConversationMessages(conversationId, { size: 50 }),
      fetchAgentPendingAction(conversationId)
    ])
    if (
      loadGeneration !== conversationLoadGeneration ||
      state.conversationId !== conversationId
    ) {
      return
    }
    if (historyResult.status === 'rejected') {
      throw historyResult.reason
    }
    const history = historyResult.value
    state.messages = (history.messages || []).map(mapHistoryMessage)
    state.historyCursor = history.nextBeforeSequence
    state.historyHasMore = Boolean(history.hasMore)

    if (actionResult.status === 'fulfilled') {
      const actionMessage = applyRecoveredAction(actionResult.value)
      if (actionMessage?.actionExecution?.status === 'EXECUTING') {
        scheduleStatusPoll(actionMessage, 0, conversationId)
      }
    } else {
      message.warning('会话消息已恢复，但操作状态加载失败')
    }
  } catch (error) {
    if (
      loadGeneration === conversationLoadGeneration &&
      state.conversationId === conversationId
    ) {
      state.messages = []
      message.error(error.response?.data?.message || '恢复会话记录失败')
    }
  } finally {
    if (loadGeneration === conversationLoadGeneration) {
      state.loadingHistory = false
      // 先让真实消息替换加载动画，再定位到当前会话的最新消息。
      await scrollToBottom()
    }
  }
}

/**
 * 使用服务端序号游标向前加载更早的会话消息。
 */
const loadMoreHistory = async () => {
  if (
    state.loadingMoreHistory ||
    !state.historyHasMore ||
    !state.historyCursor
  ) {
    return
  }
  const conversationId = state.conversationId
  const loadGeneration = conversationLoadGeneration
  const container = messageContainer.value
  const previousHeight = container?.scrollHeight || 0
  state.loadingMoreHistory = true
  try {
    const history = await fetchAgentConversationMessages(conversationId, {
      beforeSequence: state.historyCursor,
      size: 50
    })
    if (
      loadGeneration !== conversationLoadGeneration ||
      state.conversationId !== conversationId
    ) {
      return
    }

    // 历史页插入顶部时按消息标识去重，并保持用户当前阅读位置。
    const existingIds = new Set(state.messages.map((item) => item.id))
    const olderMessages = (history.messages || [])
      .map(mapHistoryMessage)
      .filter((item) => !existingIds.has(item.id))
    state.messages.unshift(...olderMessages)
    reconcileRecoveredActionMessage()
    state.historyCursor = history.nextBeforeSequence
    state.historyHasMore = Boolean(history.hasMore)
    await nextTick()
    if (container) {
      container.scrollTop += container.scrollHeight - previousHeight
    }
  } catch (error) {
    message.error(error.response?.data?.message || '加载更早消息失败')
  } finally {
    state.loadingMoreHistory = false
  }
}

/**
 * 创建新的 Agent 会话并切换到空白对话。
 */
const createConversation = async () => {
  if (state.creatingConversation) {
    return
  }
  state.creatingConversation = true
  try {
    cancelActiveConversationWork()
    conversationLoadGeneration += 1
    const result = await fetchCreateAgentConversation({
      title: '智能购票会话'
    })
    state.conversationId = result.conversationId
    state.messages = []
    state.historyCursor = null
    state.historyHasMore = false
    sessionStorage.setItem(storageKey, result.conversationId)

    // 新会话立即加入侧栏顶部，避免等待下一次分页刷新。
    state.conversations = [
      {
        conversationId: result.conversationId,
        title: '智能购票会话',
        status: 'ACTIVE',
        lastMessageSequence: 0,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString()
      },
      ...state.conversations.filter(
        (item) => item.conversationId !== result.conversationId
      )
    ]
    state.conversationTotal += 1
    message.success('已创建新的智能购票会话')
  } catch (error) {
    message.error(error.response?.data?.message || '创建会话失败')
  } finally {
    state.creatingConversation = false
  }
}

/**
 * 确保发送问题前已经存在当前用户会话。
 *
 * @returns {Promise<string>} 当前会话标识
 */
const requireConversation = async () => {
  if (!state.conversationId) {
    await createConversation()
  }
  if (!state.conversationId) {
    throw new Error('无法创建智能购票会话')
  }
  return state.conversationId
}

/**
 * 发送问题并把五类 SSE 事件转换为页面消息和确认卡片。
 *
 * @param {string} content 用户问题
 */
const sendMessage = async (content) => {
  if (state.streaming || state.loadingHistory || !content?.trim()) {
    return
  }
  const requestStartedAt = Date.now()
  let assistantMessage
  let elapsedTimer
  let requestGeneration
  let conversationId
  try {
    conversationId = await requireConversation()
    requestGeneration = ++streamGeneration
    const requestId = createAgentRequestId()
    const userMessage = {
      id: `${requestId}-user`,
      role: 'USER',
      content: content.trim()
    }
    assistantMessage = {
      id: `${requestId}-assistant`,
      role: 'ASSISTANT',
      content: '',
      pending: true,
      elapsedSeconds: 0,
      requestId
    }
    state.messages.push(userMessage, assistantMessage)
    // 后续流式增量必须写入 Vue 代理对象，直接修改入列前的原始对象不会触发页面重绘。
    assistantMessage = state.messages[state.messages.length - 1]
    // 按实际经过时间刷新等待秒数，浏览器后台降频后也不会因定时器次数而产生累计误差。
    elapsedTimer = window.setInterval(() => {
      assistantMessage.elapsedSeconds = Math.floor(
        (Date.now() - requestStartedAt) / 1000
      )
    }, 1000)
    state.streaming = true
    await scrollToBottom()

    // 同一次消息的请求标识和幂等键保持一致，网络层不得自动换键重试。
    streamController = new AbortController()
    activeStreamRequest = { conversationId, requestId }
    await streamAgentChat({
      conversationId,
      message: content.trim(),
      requestId,
      idempotencyKey: requestId,
      signal: streamController.signal,
      onEvent: (eventName, event) => {
        // 会话切换后丢弃旧流中已经在网络途中的事件。
        if (
          requestGeneration === streamGeneration &&
          state.conversationId === conversationId
        ) {
          handleAgentEvent(eventName, event, assistantMessage)
        }
      }
    })
  } catch (error) {
    const requestIsActive =
      requestGeneration === streamGeneration &&
      state.conversationId === conversationId
    if (!requestIsActive) {
      return
    }
    if (error.name === 'AbortError') {
      if (assistantMessage && !assistantMessage.content) {
        assistantMessage.content = '已停止生成'
      }
    } else {
      if (assistantMessage) {
        assistantMessage.error = error.message || '智能体服务暂时不可用'
        assistantMessage.failureCategory = error.failureCategory
      }
      if (error.status === 401) {
        message.error('用户登录状态已失效')
        router.push('/login')
      } else {
        message.error(error.message || '智能体服务请求失败')
      }
    }
  } finally {
    window.clearInterval(elapsedTimer)
    if (assistantMessage) {
      // 流结束时再按真实时间校准一次，并保留最终耗时供用户查看。
      assistantMessage.elapsedSeconds = Math.floor(
        (Date.now() - requestStartedAt) / 1000
      )
    }
    if (
      requestGeneration === streamGeneration &&
      state.conversationId === conversationId
    ) {
      if (assistantMessage) {
        assistantMessage.pending = false
      }
      state.streaming = false
      streamController = null
      activeStreamRequest = null
      touchActiveConversation()
      await scrollToBottom()
    }
  }
}

/**
 * 更新当前会话的本地活跃时间并移动到侧栏顶部。
 */
const touchActiveConversation = () => {
  const index = state.conversations.findIndex(
    (item) => item.conversationId === state.conversationId
  )
  if (index < 0) {
    return
  }

  // 后端会在消息写入时更新会话时间，本地先同步排序以减少界面跳动。
  const conversation = state.conversations[index]
  conversation.updatedAt = new Date().toISOString()
  state.conversations.splice(index, 1)
  state.conversations.unshift(conversation)
}

/**
 * 处理服务端 SSE 事件并更新当前助手消息。
 *
 * @param {string} eventName SSE 事件名
 * @param {object} event Agent 事件数据
 * @param {object} assistantMessage 当前助手消息
 */
const handleAgentEvent = (eventName, event, assistantMessage) => {
  const type = (event.type || eventName || '').toLowerCase()
  if (type === 'meta') {
    assistantMessage.turnId = event.turnId
    assistantMessage.topicId = event.topicId
  } else if (type === 'delta') {
    assistantMessage.content += event.delta || ''
  } else if (type === 'action_required') {
    assistantMessage.action = event.action
  } else if (type === 'done') {
    assistantMessage.content = event.content || assistantMessage.content
    assistantMessage.pending = false
  } else if (type === 'error') {
    assistantMessage.error = event.message || '智能体回答失败'
    assistantMessage.failureCategory = event.failureCategory
    assistantMessage.pending = false
  }
  scrollToBottom()
}

/**
 * 使用一次性确认令牌执行用户已经核对的高风险操作。
 *
 * @param {object} chatMessage 包含操作卡片的助手消息
 */
const confirmAction = async (chatMessage) => {
  const action = chatMessage.action
  state.confirmingActionId = action.actionId
  action.confirmRequestId = action.confirmRequestId || createAgentRequestId()
  try {
    const result = await fetchConfirmAgentAction(
      action.actionId,
      action.confirmationToken,
      action.confirmRequestId
    )
    chatMessage.actionExecution = result
    if (result.status === 'EXECUTING') {
      scheduleStatusPoll(chatMessage)
    }
  } catch (error) {
    // 确认响应丢失时不能换幂等键盲目重试，先查询服务端持久化状态。
    await refreshAction(chatMessage, false)
    const failure = error.response?.data
    if (!chatMessage.actionExecution && failure?.failureCategory) {
      chatMessage.actionExecution = {
        actionId: action.actionId,
        actionType: action.actionType,
        status: failure.failureCategory.endsWith('_RESULT_UNKNOWN')
          ? 'UNKNOWN'
          : action.status,
        failureCategory: failure.failureCategory
      }
    }
    message.error(failure?.message || '操作确认失败，请先刷新状态')
  } finally {
    state.confirmingActionId = null
  }
}

/**
 * 查询操作最新持久化状态。
 *
 * @param {object} chatMessage 包含操作卡片的助手消息
 * @param {boolean} showFeedback 是否展示查询反馈
 * @param {boolean} schedulePolling 是否继续轮询执行中状态
 */
const refreshAction = async (
  chatMessage,
  showFeedback = true,
  schedulePolling = true
) => {
  const actionId = chatMessage.action.actionId
  state.refreshingActionId = actionId
  try {
    const result = await fetchAgentActionStatus(actionId)
    chatMessage.actionExecution = result
    if (result.status === 'EXECUTING' && schedulePolling) {
      scheduleStatusPoll(chatMessage)
    } else if (showFeedback) {
      message.success('操作状态已更新')
    }
  } catch (error) {
    if (showFeedback) {
      message.error(error.response?.data?.message || '查询操作状态失败')
    }
  } finally {
    state.refreshingActionId = null
  }
}

/**
 * 对短暂处于执行中的操作进行有限次数状态轮询。
 *
 * @param {object} chatMessage 包含操作卡片的助手消息
 * @param {number} attempt 当前轮询次数
 * @param {string} conversationId 轮询所属会话标识
 */
const scheduleStatusPoll = (
  chatMessage,
  attempt = 0,
  conversationId = state.conversationId
) => {
  const actionId = chatMessage.action.actionId
  if (
    conversationId !== state.conversationId ||
    attempt >= 20 ||
    terminalStatuses.includes(chatMessage.actionExecution?.status) ||
    pollTimers.has(actionId)
  ) {
    return
  }
  const timer = window.setTimeout(async () => {
    pollTimers.delete(actionId)
    if (conversationId !== state.conversationId) {
      return
    }
    await refreshAction(chatMessage, false, false)
    scheduleStatusPoll(chatMessage, attempt + 1, conversationId)
  }, 1500)
  pollTimers.set(actionId, timer)
}

/**
 * 清理全部操作状态轮询，防止新会话继续更新旧消息。
 */
const clearStatusPolls = () => {
  pollTimers.forEach((timer) => window.clearTimeout(timer))
  pollTimers.clear()
}

/**
 * 主动停止当前生成并立即更新页面，服务端轮次状态由取消接口同步处理。
 */
const stopStreaming = () => {
  const activeRequest = cancelActiveConversationWork()
  if (!activeRequest) {
    return
  }
  const assistantMessage = state.messages.find(
    (item) => item.id === `${activeRequest.requestId}-assistant`
  )
  if (assistantMessage) {
    // 先完成页面状态更新，用户不需要等待后端取消请求返回。
    assistantMessage.pending = false
    if (!assistantMessage.content) {
      assistantMessage.content = '已停止生成'
    }
  }
  scrollToBottom()
}

/**
 * 等待 DOM 更新后滚动到最新消息。
 */
const scrollToBottom = async () => {
  await nextTick()
  if (messageContainer.value) {
    messageContainer.value.scrollTop = messageContainer.value.scrollHeight
  }
}

onMounted(async () => {
  const savedConversationId = state.conversationId
  const loaded = await loadConversations(true)
  if (!loaded) {
    return
  }
  const targetConversation = state.conversations.find(
    (item) => item.conversationId === savedConversationId
  )
  if (targetConversation) {
    await selectConversation(targetConversation.conversationId, true)
  } else if (state.conversations.length) {
    await selectConversation(state.conversations[0].conversationId, true)
  } else {
    await createConversation()
  }
})

onBeforeUnmount(() => {
  conversationLoadGeneration += 1
  cancelActiveConversationWork()
})
</script>

<style lang="scss" scoped>
.agent-page {
  height: calc(100vh - 114px);
  min-height: 620px;
}

.agent-row,
.agent-column {
  height: 100%;
}

.chat-card {
  height: 100%;

  :deep(.ant-card-body) {
    display: flex;
    flex-direction: column;
    height: calc(100% - 57px);
    padding: 0;
  }
}

.chat-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.message-container {
  flex: 1;
  padding: 22px;
  overflow-y: auto;
  background: #f7f8fa;
}

.history-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
}

.history-more {
  display: flex;
  justify-content: center;
  margin: -10px 0 16px;
}

.empty-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100%;
}

.empty-robot {
  color: #1e71bd;
  font-size: 68px;
}

.starter-title {
  margin: 12px 0;
  color: #8f9598;
}
</style>
