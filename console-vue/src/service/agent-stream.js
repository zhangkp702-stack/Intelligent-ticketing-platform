import Cookie from 'js-cookie'

const DEFAULT_IDLE_TIMEOUT_MS = 70_000

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
 * @param {string} options.conversationId 会话标识
 * @param {string} options.requestId 本轮请求标识
 * @returns {Promise<void>} 服务端确认处理完成后结束
 */
const cancelAgentChat = async ({ conversationId, requestId }) => {
  const response = await fetch('/api/agent-service/chat/cancel', {
    method: 'POST',
    headers: {
      Authorization: Cookie.get('token') ?? '',
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ conversationId, requestId })
  })
  if (!response.ok) {
    const errorBody = await response.json().catch(() => null)
    throw new Error(errorBody?.message || '停止生成请求失败')
  }
}

/**
 * 解析单个 SSE 事件块。
 *
 * @param {string} block SSE 原始事件块
 * @returns {{eventName: string, data: object} | null} 结构化事件
 */
const parseEventBlock = (block) => {
  let eventName = 'message'
  const dataLines = []
  block.split('\n').forEach((line) => {
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    }
  })
  if (!dataLines.length) {
    return null
  }
  return {
    eventName,
    data: JSON.parse(dataLines.join('\n'))
  }
}

/**
 * 使用 POST 请求消费 Agent SSE，支持认证头、幂等键和主动中止。
 *
 * @param {object} options 流式请求参数
 * @returns {Promise<void>} 流读取完成后结束
 */
const streamAgentChat = async ({
  conversationId,
  message,
  requestId,
  idempotencyKey,
  signal,
  onEvent,
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
    const response = await fetch('/api/agent-service/chat/stream', {
      method: 'POST',
      headers: {
        Authorization: Cookie.get('token') ?? '',
        'Content-Type': 'application/json',
        'X-Request-Id': requestId,
        'Idempotency-Key': idempotencyKey
      },
      body: JSON.stringify({
        conversationId,
        message
      }),
      signal: requestController.signal
    })

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

    // 服务端必须以 DONE 或 ERROR 结束；静默断流统一转换为可理解的失败提示。
    if (!terminalReceived) {
      const error = new Error('智能体连接已提前结束，请重新发送问题')
      error.failureCategory = 'STREAM_CLOSED'
      throw error
    }
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

export { cancelAgentChat, createAgentRequestId, streamAgentChat }
