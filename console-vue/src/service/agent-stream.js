import Cookie from 'js-cookie'

const DEFAULT_IDLE_TIMEOUT_MS = 70_000
const MAX_RECONNECT_ATTEMPTS = 3

/**
 * 生成满足 Agent 数据库长度约束的请求标识。
 *
 * @returns {string} 不包含分隔符的请求标识
 */
const createAgentRequestId = () => {
  if (window.crypto?.randomUUID) {
    return window.crypto.randomUUID().replaceAll('-', '')
  }
  return `${Date.now()}${Math.random().toString(16).slice(2)}`
}

/**
 * 通知服务端取消指定会话中的生成任务，避免仅断开浏览器读取后模型仍继续运行。
 *
 * @param {object} options 取消请求参数
 * @param {string} options.turnId 服务端轮次标识
 * @returns {Promise<void>} 服务端确认处理完成后结束
 */
const cancelAgentChat = async ({ turnId }) => {
  const response = await fetch(`/api/agent-service/turns/${turnId}/cancel`, {
    method: 'POST',
    headers: {
      Authorization: Cookie.get('token') ?? ''
    }
  })
  if (!response.ok) {
    const errorBody = await response.json().catch(() => null)
    throw new Error(errorBody?.message || '停止生成请求失败')
  }
}

/**
 * 向服务端预创建尚未执行的轮次，业务标识和提交令牌均不由浏览器生成。
 *
 * @param {string} conversationId 当前会话标识
 * @returns {Promise<{turnId: string, submissionToken: string, expiresAt: string}>} 轮次提交凭证
 */
const prepareAgentTurn = async (conversationId) => {
  const response = await fetch(
    `/api/agent-service/conversations/${conversationId}/turns`,
    {
      method: 'POST',
      headers: {
        Authorization: Cookie.get('token') ?? ''
      }
    }
  )
  if (!response.ok) {
    const errorBody = await response.json().catch(() => null)
    const error = new Error(errorBody?.message || '创建智能体轮次失败')
    error.status = response.status
    error.failureCategory = errorBody?.failureCategory
    throw error
  }
  return response.json()
}

/**
 * 查询服务端轮次的持久化状态，用于网络异常后的最终结果恢复。
 *
 * @param {string} turnId 服务端轮次标识
 * @returns {Promise<object>} 轮次状态和已完成回答
 */
const fetchAgentTurnState = async (turnId) => {
  const response = await fetch(`/api/agent-service/turns/${turnId}`, {
    headers: {
      Authorization: Cookie.get('token') ?? ''
    }
  })
  if (!response.ok) {
    const errorBody = await response.json().catch(() => null)
    throw new Error(errorBody?.message || '查询智能体轮次失败')
  }
  return response.json()
}

/**
 * 解析单个 SSE 事件块。
 *
 * @param {string} block SSE 原始事件块
 * @returns {{eventName: string, eventId: number | null, data: object} | null} 结构化事件
 */
const parseEventBlock = (block) => {
  let eventName = 'message'
  let eventId = null
  const dataLines = []
  block.split('\n').forEach((line) => {
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
    } else if (line.startsWith('id:')) {
      const parsedId = Number(line.slice(3).trim())
      if (Number.isSafeInteger(parsedId) && parsedId >= 0) {
        eventId = parsedId
      }
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    }
  })
  if (!dataLines.length) {
    return null
  }
  return {
    eventName,
    eventId,
    data: JSON.parse(dataLines.join('\n'))
  }
}

/**
 * 完成一次 Agent SSE 网络读取，并持续刷新空闲超时。
 *
 * @param {object} options 单次网络尝试参数
 * @returns {Promise<{terminalReceived: boolean, lastEventId: number}>} 本次读取边界
 */
const consumeAgentStream = async ({
  turnId,
  conversationId,
  message,
  attemptId,
  submissionToken,
  signal,
  onEvent,
  onEventId,
  lastEventId,
  resume,
  idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS
}) => {
  const requestController = new AbortController()
  let idleTimer
  let idleTimedOut = false
  let terminalReceived = false
  const abortFromCaller = () => requestController.abort(signal?.reason)
  const refreshIdleTimeout = () => {
    clearTimeout(idleTimer)
    idleTimer = setTimeout(() => {
      // 长时间没有任何响应字节时主动关闭网络流，避免页面永久处于生成状态。
      idleTimedOut = true
      requestController.abort()
    }, idleTimeoutMs)
  }

  if (signal?.aborted) {
    abortFromCaller()
  } else {
    signal?.addEventListener('abort', abortFromCaller, { once: true })
  }
  refreshIdleTimeout()

  try {
    const response = await fetch(
      `/api/agent-service/turns/${turnId}/stream`,
      {
        method: 'POST',
        headers: {
          Authorization: Cookie.get('token') ?? '',
          'Content-Type': 'application/json',
          'X-Attempt-Id': attemptId,
          ...(resume ? { 'Last-Event-ID': String(lastEventId) } : {})
        },
        body: JSON.stringify({
          conversationId,
          message,
          submissionToken
        }),
        signal: requestController.signal
      }
    )

    // SSE 尚未开始时仍按普通 HTTP 错误响应处理。
    if (!response.ok) {
      const errorBody = await response.json().catch(() => null)
      const error = new Error(errorBody?.message || '智能体服务请求失败')
      error.status = response.status
      error.failureCategory = errorBody?.failureCategory
      throw error
    }
    if (!response.body) {
      throw new Error('浏览器未提供流式响应内容')
    }

    // TextDecoder 的 stream 模式可以避免中文字符被网络分片截断。
    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    while (true) {
      const { value, done } = await reader.read()
      if (!done) {
        refreshIdleTimeout()
      }
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      buffer = buffer.replace(/\r\n/g, '\n')
      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const block = buffer.slice(0, boundary).trim()
        buffer = buffer.slice(boundary + 2)
        if (block) {
          const event = parseEventBlock(block)
          if (event) {
            if (event.eventId !== null) {
              lastEventId = Math.max(lastEventId, event.eventId)
              onEventId(lastEventId)
            }
            const eventName = event.eventName.toLowerCase()
            if (eventName === 'done' || eventName === 'error') {
              terminalReceived = true
            }
            onEvent(event.eventName, event.data)
          }
        }
        boundary = buffer.indexOf('\n\n')
      }
      if (done) {
        break
      }
    }

    return { terminalReceived, lastEventId }
  } catch (error) {
    if (idleTimedOut) {
      const timeoutError = new Error(
        '智能体响应时间过长，本次生成已停止，请稍后重试'
      )
      timeoutError.name = 'AgentStreamTimeoutError'
      timeoutError.failureCategory = 'CHAT_TIMEOUT'
      throw timeoutError
    }
    throw error
  } finally {
    clearTimeout(idleTimer)
    signal?.removeEventListener('abort', abortFromCaller)
  }
}

/**
 * 使用同一服务端 Turn 消费 Agent SSE；网络中断后携带 Last-Event-ID 自动续传。
 *
 * @param {object} options 流式请求参数
 * @returns {Promise<void>} 收到 DONE 或 ERROR 后结束
 */
const streamAgentChat = async (options) => {
  let lastEventId = 0
  let reconnectAttempts = 0

  while (true) {
    try {
      // attemptId 只标识一次网络连接；重连保持 turnId 不变并生成新的观测标识。
      const result = await consumeAgentStream({
        ...options,
        attemptId:
          reconnectAttempts === 0
            ? options.attemptId
            : createAgentRequestId(),
        lastEventId,
        onEventId: (eventId) => {
          // 即使本次连接随后抛出网络异常，也从已经处理完的最后事件之后恢复。
          lastEventId = Math.max(lastEventId, eventId)
        },
        resume: reconnectAttempts > 0
      })
      lastEventId = result.lastEventId
      if (result.terminalReceived) {
        return
      }

      // HTTP 正常结束但缺少终态与网络断开等价，应从持久化游标继续读取。
      const closedError = new Error('智能体连接已提前结束')
      closedError.failureCategory = 'STREAM_CLOSED'
      throw closedError
    } catch (error) {
      if (options.signal?.aborted) {
        throw error
      }
      if (error.status && error.status < 500) {
        // 参数、身份和状态机冲突无法通过重连恢复，直接交给页面展示。
        throw error
      }
      if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
        throw error
      }
      reconnectAttempts += 1
    }
  }
}

export {
  cancelAgentChat,
  createAgentRequestId,
  fetchAgentTurnState,
  prepareAgentTurn,
  streamAgentChat
}
