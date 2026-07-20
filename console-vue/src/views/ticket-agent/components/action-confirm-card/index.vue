<template>
  <Card class="action-card" size="small" :bordered="true">
    <template #title>
      <Space>
        <ExclamationCircleFilled :style="{ color: actionColor }" />
        <span>{{ actionLabel }}</span>
        <Tag :color="statusColor">{{ statusLabel }}</Tag>
      </Space>
    </template>
    <Alert
      :type="alertType"
      show-icon
      :message="action.summary"
      :description="actionDescription"
    />
    <Descriptions class="action-detail" size="small" :column="1" bordered>
      <DescriptionsItem label="操作编号">
        {{ action.actionId }}
      </DescriptionsItem>
      <DescriptionsItem label="确认截止时间">
        {{ expiresAtText }}
      </DescriptionsItem>
      <DescriptionsItem v-if="execution?.orderSn" label="订单号">
        {{ execution.orderSn }}
      </DescriptionsItem>
      <DescriptionsItem
        v-if="execution?.result?.refundAmount !== undefined"
        label="退款金额"
      >
        <span class="refund-amount">
          ￥{{ (execution.result.refundAmount / 100).toFixed(2) }}
        </span>
      </DescriptionsItem>
      <DescriptionsItem
        v-if="execution?.result?.status !== undefined"
        label="业务状态"
      >
        {{ execution.result.status }}
      </DescriptionsItem>
      <DescriptionsItem
        v-if="execution?.result?.cancelled !== undefined"
        label="取消结果"
      >
        {{ execution.result.cancelled ? '已取消' : '未确认取消' }}
      </DescriptionsItem>
      <DescriptionsItem v-if="execution?.failureCategory" label="失败分类">
        {{ execution.failureCategory }}
      </DescriptionsItem>
    </Descriptions>

    <Table
      v-if="purchaseTickets.length"
      class="ticket-result"
      size="small"
      :columns="ticketColumns"
      :data-source="purchaseTickets"
      :pagination="false"
      row-key="seatNumber"
    />

    <div class="action-footer">
      <span
        v-if="currentStatus === 'AWAITING_CONFIRMATION'"
        class="expires-tip"
      >
        {{ remainingText }}
      </span>
      <span v-else></span>
      <Space>
        <Button
          v-if="currentStatus === 'UNKNOWN'"
          @click="$emit('view-orders')"
        >
          查看我的订单
        </Button>
        <Button
          v-if="['EXECUTING', 'UNKNOWN'].includes(currentStatus)"
          :loading="refreshing"
          @click="$emit('refresh')"
        >
          刷新状态
        </Button>
        <Button
          v-if="currentStatus === 'AWAITING_CONFIRMATION'"
          type="primary"
          :danger="action.actionType !== 'TICKET_PURCHASE'"
          :loading="confirming"
          :disabled="expired || confirming"
          @click="$emit('confirm')"
        >
          {{ confirmButtonText }}
        </Button>
      </Space>
    </div>
  </Card>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  DescriptionsItem,
  Space,
  Table,
  Tag
} from 'ant-design-vue'
import { ExclamationCircleFilled } from '@ant-design/icons-vue'
import dayjs from 'dayjs'
import {
  AGENT_ACTION_STATUS_MAP,
  AGENT_ACTION_TYPE_MAP,
  SEAT_CLASS_TYPE_LIST,
  TICKET_TYPE_LIST
} from '@/constants'

const props = defineProps({
  action: {
    type: Object,
    required: true
  },
  execution: Object,
  confirming: Boolean,
  refreshing: Boolean
})

defineEmits(['confirm', 'refresh', 'view-orders'])

const now = ref(Date.now())
let countdownTimer

const timeExpired = computed(
  () => new Date(props.action.confirmationExpiresAt).getTime() <= now.value
)
const currentStatus = computed(
  () =>
    props.execution?.status ||
    (timeExpired.value ? 'EXPIRED' : props.action.status)
)
const actionLabel = computed(
  () => AGENT_ACTION_TYPE_MAP[props.action.actionType] || '高风险操作'
)
const statusLabel = computed(
  () => AGENT_ACTION_STATUS_MAP[currentStatus.value] || currentStatus.value
)
const actionColor = computed(() =>
  props.action.actionType === 'TICKET_PURCHASE' ? '#1e71bd' : '#fa8c16'
)
const statusColor = computed(() => {
  const colors = {
    AWAITING_CONFIRMATION: 'orange',
    EXECUTING: 'blue',
    SUCCEEDED: 'green',
    UNKNOWN: 'red',
    EXPIRED: 'default',
    FAILED: 'red',
    CANCELLED: 'default'
  }
  return colors[currentStatus.value] || 'default'
})
const alertType = computed(() => {
  if (currentStatus.value === 'SUCCEEDED') return 'success'
  if (['UNKNOWN', 'FAILED'].includes(currentStatus.value)) return 'error'
  return 'warning'
})
const expiresAtText = computed(() =>
  dayjs(props.action.confirmationExpiresAt).format('YYYY-MM-DD HH:mm:ss')
)
const remainingMilliseconds = computed(
  () => new Date(props.action.confirmationExpiresAt).getTime() - now.value
)
const expired = computed(
  () => currentStatus.value === 'EXPIRED' || timeExpired.value
)
const remainingText = computed(() => {
  if (expired.value) {
    return '确认已过期，请重新生成操作草案'
  }
  const seconds = Math.ceil(remainingMilliseconds.value / 1000)
  return `请在 ${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒内确认`
})
const confirmButtonText = computed(() => {
  const labels = {
    TICKET_PURCHASE: '确认购票',
    TICKET_CANCEL: '确认取消订单',
    TICKET_REFUND: '确认退票'
  }
  return labels[props.action.actionType] || '确认执行'
})
const actionDescription = computed(() => {
  if (currentStatus.value === 'UNKNOWN') {
    return '下游结果暂时无法确认，请勿重复提交，先查询本人订单和支付状态。'
  }
  if (currentStatus.value === 'FAILED') {
    return '操作已明确失败，没有生成可确认的订单。请核对席别、乘车人和余票后重新生成操作草案。'
  }
  if (currentStatus.value === 'EXPIRED' || expired.value) {
    return '该确认信息已经失效，请重新向智能体描述操作要求。'
  }
  if (currentStatus.value === 'SUCCEEDED') {
    return '操作已经完成，结果已保存。'
  }
  if (currentStatus.value === 'CANCELLED') {
    return '该操作已经取消，不会继续执行。'
  }
  return '请核对关键信息。只有点击确认后，服务端才会执行真实业务操作。'
})
const purchaseTickets = computed(() => props.execution?.result?.tickets || [])

const ticketColumns = [
  {
    title: '乘车人',
    dataIndex: 'realName',
    key: 'realName'
  },
  {
    title: '席别',
    dataIndex: 'seatType',
    key: 'seatType',
    customRender: ({ text }) =>
      SEAT_CLASS_TYPE_LIST.find((item) => item.code === text)?.label || text
  },
  {
    title: '座位',
    key: 'seat',
    customRender: ({ record }) =>
      `${record.carriageNumber || '--'}车 ${record.seatNumber || '--'}`
  },
  {
    title: '票种',
    dataIndex: 'ticketType',
    key: 'ticketType',
    customRender: ({ text }) =>
      TICKET_TYPE_LIST.find((item) => item.value === text)?.label || text
  },
  {
    title: '金额',
    dataIndex: 'amount',
    key: 'amount',
    customRender: ({ text }) => `￥${(text / 100).toFixed(2)}`
  }
]

onMounted(() => {
  // 倒计时只更新展示，不在浏览器端决定服务端令牌是否有效。
  countdownTimer = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  window.clearInterval(countdownTimer)
})
</script>

<style lang="scss" scoped>
.action-card {
  margin: -10px 0 22px 44px;
  max-width: calc(75% - 32px);
  border-color: #f0ad4e;
}

.action-detail {
  margin-top: 12px;
}

.ticket-result {
  margin-top: 12px;
}

.action-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 14px;
}

.expires-tip {
  color: #fa8c16;
  font-size: 12px;
}

.refund-amount {
  color: #fc8302;
  font-weight: bold;
}
</style>
