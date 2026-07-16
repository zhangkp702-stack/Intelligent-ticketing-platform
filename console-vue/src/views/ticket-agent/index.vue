<template>
  <div class="agent-page">
    <Row :gutter="16" class="agent-row">
      <Col :span="6" class="agent-column">
        <ConversationPanel
          :conversation-id="state.conversationId"
          :creating="state.creatingConversation"
          :streaming="state.streaming"
          @create="createConversation"
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
            <div v-if="!state.messages.length" class="empty-content">
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
          </div>
          <ChatInput
            :disabled="state.creatingConversation"
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
  Tag,
  message
} from 'ant-design-vue'
import { RobotOutlined } from '@ant-design/icons-vue'
import Cookie from 'js-cookie'
import { useRouter } from 'vue-router'
import {
  fetchAgentActionStatus,
  fetchConfirmAgentAction,
  fetchCreateAgentConversation
} from '@/service'
import { createAgentRequestId, streamAgentChat } from '@/service/agent-stream'
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

const state = reactive({
  conversationId: sessionStorage.getItem(storageKey),
  messages: [],
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
 * 创建新的 Agent 会话并清空当前页面消息。
 */
const createConversation = async () => {
  if (state.streaming) {
    return
  }
  state.creatingConversation = true
  try {
    clearStatusPolls()
    const result = await fetchCreateAgentConversation({
      title: '智能购票会话'
    })
    state.conversationId = result.conversationId
    state.messages = []
    sessionStorage.setItem(storageKey, result.conversationId)
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
  if (state.streaming || !content?.trim()) {
    return
  }
  try {
    const conversationId = await requireConversation()
    const requestId = createAgentRequestId()
    const userMessage = {
      id: `${requestId}-user`,
      role: 'USER',
      content: content.trim()
    }
    const assistantMessage = {
      id: `${requestId}-assistant`,
      role: 'ASSISTANT',
      content: '',
      pending: true,
      requestId
    }
    state.messages.push(userMessage, assistantMessage)
    state.streaming = true
    await scrollToBottom()

    // 同一次消息的请求标识和幂等键保持一致，网络层不得自动换键重试。
    streamController = new AbortController()
    await streamAgentChat({
      conversationId,
      message: content.trim(),
      requestId,
      idempotencyKey: requestId,
      signal: streamController.signal,
      onEvent: (eventName, event) => {
        handleAgentEvent(eventName, event, assistantMessage)
      }
    })
  } catch (error) {
    if (error.name === 'AbortError') {
      const assistantMessage = state.messages
        .slice()
        .reverse()
        .find((item) => item.role === 'ASSISTANT' && item.pending)
      if (assistantMessage && !assistantMessage.content) {
        assistantMessage.content = '已停止生成'
      }
    } else {
      const assistantMessage = state.messages
        .slice()
        .reverse()
        .find((item) => item.role === 'ASSISTANT' && item.pending)
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
    const assistantMessage = state.messages
      .slice()
      .reverse()
      .find((item) => item.role === 'ASSISTANT' && item.pending)
    if (assistantMessage) {
      assistantMessage.pending = false
    }
    state.streaming = false
    streamController = null
    await scrollToBottom()
  }
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
 */
const scheduleStatusPoll = (chatMessage, attempt = 0) => {
  const actionId = chatMessage.action.actionId
  if (
    attempt >= 20 ||
    terminalStatuses.includes(chatMessage.actionExecution?.status) ||
    pollTimers.has(actionId)
  ) {
    return
  }
  const timer = window.setTimeout(async () => {
    pollTimers.delete(actionId)
    await refreshAction(chatMessage, false, false)
    scheduleStatusPoll(chatMessage, attempt + 1)
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
 * 主动停止当前浏览器流读取，不改变已经落库的服务端轮次状态。
 */
const stopStreaming = () => {
  streamController?.abort()
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
  if (!state.conversationId) {
    await createConversation()
  }
})

onBeforeUnmount(() => {
  streamController?.abort()
  clearStatusPolls()
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
