import Cookie from 'js-cookie'

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
  onEvent
}) => {
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
    signal
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
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
    buffer = buffer.replace(/\r\n/g, '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary).trim()
      buffer = buffer.slice(boundary + 2)
      if (block) {
        const event = parseEventBlock(block)
        if (event) {
          onEvent(event.eventName, event.data)
        }
      }
      boundary = buffer.indexOf('\n\n')
    }
    if (done) {
      break
    }
  }
}

export { createAgentRequestId, streamAgentChat }
