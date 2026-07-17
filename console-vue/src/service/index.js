import http from './axios'

const fetchLogin = async (body) => {
  const { data } = await http({
    method: 'POST',
    url: '/api/user-service/v1/login',
    data: body
  })
  http.defaults.headers.common['Authorization'] = data.data?.accessToken
  return data
}

const fetchRegister = async (body) => {
  const { data } = await http({
    method: 'POST',
    url: '/api/user-service/register',
    data: body
  })
  return data
}

const fetchTicketSearch = async (params) => {
  const { data } = await http({
    method: 'GET',
    url: '/api/ticket-service/ticket/query',
    params
  })
  return data
}

const fetchRegionStation = async (params) => {
  const { data } = await http({
    method: 'GET',
    url: '/api/ticket-service/region-station/query',
    params
  })
  return data
}

const fetchPassengerList = async (params) => {
  const { data } = await http({
    method: 'GET',
    url: '/api/user-service/passenger/query',
    params
  })
  return data
}
const fetchDeletePassenger = async (body) => {
  const { data } = await http({
    method: 'POST',
    url: '/api/user-service/passenger/remove',
    data: body
  })
  return data
}

const fetchAddPassenger = async (body) => {
  const { data } = await http({
    method: 'POST',
    url: '/api/user-service/passenger/save',
    data: body
  })
  return data
}

const fetchEditPassenger = async (body) => {
  const { data } = await http({
    method: 'POST',
    url: '/api/user-service/passenger/update',
    data: body
  })
  return data
}
const fetchLogout = async (body) => {
  const { data } = await http({
    method: 'GET',
    url: '/api/user-service/logout',
    data: body
  })
  http.defaults.headers.common['Authorization'] = null
  return data
}

const fetchBuyTicket = async (body) => {
  const { data } = await http({
    method: 'POST',
    url: '/api/ticket-service/ticket/purchase/v2',
    data: body
  })

  return data
}

const fetchOrderBySn = async (params) => {
  const { data } = await http({
    method: 'GET',
    url: '/api/order-service/order/ticket/query',
    params
  })
  return data
}

const fetchPay = async (body) => {
  const { data } = await http({
    method: 'POST',
    url: '/api/pay-service/pay/create',
    data: body
  })
  return data
}

const fetchStationAll = async () => {
  const { data } = await http({
    method: 'GET',
    url: '/api/ticket-service/station/all'
  })
  return data
}

/**
 * 查询指定出发站沿现有车次线路可以直达的目的站。
 *
 * @param {{departureCode: string}} params 出发站编码参数
 * @returns {Promise<object>} 可达站点接口响应
 */
const fetchReachableStations = async (params) => {
  const { data } = await http({
    method: 'GET',
    url: '/api/ticket-service/station/reachable',
    params
  })
  return data
}

const fechUserInfo = async (params) => {
  const { data } = await http({
    method: 'GET',
    url: '/api/user-service/query',
    params
  })
  return data
}

const fetchTrainStation = async (params) => {
  const { data } = await http({
    method: 'GET',
    url: '/api/ticket-service/train-station/query',
    params
  })
  return data
}

const fetchTicketList = async (params) => {
  const { data } = await http({
    method: 'GET',
    url: '/api/order-service/order/ticket/page',
    params
  })
  return data
}

const fetchOrderCancel = async (body) => {
  const { data } = await http({
    method: 'POST',
    url: '/api/ticket-service/ticket/cancel',
    data: body
  })
  return data
}

const fetchUserUpdate = async (body) => {
  const { data } = await http({
    method: 'POST',
    url: '/api/user-service/update',
    data: body
  })
  return data
}

const fetchOrderStatus = async (params) => {
  const { data } = await http({
    method: 'GET',
    url: '/api/pay-service/pay/query/order-sn',
    params
  })
  return data
}

const fetchMyTicket = async (params) => {
  const { data } = await http({
    method: 'GET',
    url: '/api/order-service/order/ticket/self/page',
    params
  })
  return data
}

const fetchRefundTicket = async (body) => {
  const { data } = await http({
    method: 'POST',
    url: '/api/ticket-service/ticket/refund',
    data: body
  })
  return data
}

const fetchCreateAgentConversation = async (body) => {
  const { data } = await http({
    method: 'POST',
    url: '/api/agent-service/conversations',
    data: body
  })
  return data
}

const fetchAgentConversations = async (params) => {
  const { data } = await http({
    method: 'GET',
    url: '/api/agent-service/conversations',
    params
  })
  return data
}

const fetchAgentConversationMessages = async (conversationId, params) => {
  const { data } = await http({
    method: 'GET',
    url: `/api/agent-service/conversations/${conversationId}/messages`,
    params
  })
  return data
}

const fetchAgentPendingAction = async (conversationId) => {
  const { data } = await http({
    method: 'GET',
    url: `/api/agent-service/conversations/${conversationId}/pending-action`
  })
  return data || null
}

const fetchConfirmAgentAction = async (
  actionId,
  confirmationToken,
  requestId
) => {
  const { data } = await http({
    method: 'POST',
    url: `/api/agent-service/actions/${actionId}/confirm`,
    headers: {
      'X-Request-Id': requestId,
      'Idempotency-Key': requestId
    },
    data: {
      confirmationToken
    }
  })
  return data
}

const fetchAgentActionStatus = async (actionId) => {
  const { data } = await http({
    method: 'GET',
    url: `/api/agent-service/actions/${actionId}`
  })
  return data
}

export {
  fetchLogin,
  fetchRegister,
  fetchTicketSearch,
  fetchRegionStation,
  fetchPassengerList,
  fetchDeletePassenger,
  fetchAddPassenger,
  fetchEditPassenger,
  fetchLogout,
  fetchBuyTicket,
  fetchOrderBySn,
  fetchPay,
  fetchStationAll,
  fetchReachableStations,
  fechUserInfo,
  fetchTrainStation,
  fetchTicketList,
  fetchOrderCancel,
  fetchOrderStatus,
  fetchUserUpdate,
  fetchMyTicket,
  fetchRefundTicket,
  fetchCreateAgentConversation,
  fetchAgentConversations,
  fetchAgentConversationMessages,
  fetchAgentPendingAction,
  fetchConfirmAgentAction,
  fetchAgentActionStatus
}
