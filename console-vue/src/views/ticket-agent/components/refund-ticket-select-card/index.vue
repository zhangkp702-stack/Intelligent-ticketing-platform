<template>
  <Card class="refund-ticket-card" size="small" title="选择需要退票的车票">
    <Alert type="warning" show-icon :message="workflow.prompt" />
    <CheckboxGroup v-model:value="selectedItemIds" class="ticket-options">
      <Checkbox
        v-for="ticket in workflow.tickets"
        :key="ticket.orderItemId"
        :value="ticket.orderItemId"
        class="ticket-option"
      >
        <span class="ticket-title">{{ ticket.realName || '未知乘车人' }}</span>
        <span class="ticket-detail">
          {{ ticket.carriageNumber || '-' }} 车厢
          {{ ticket.seatNumber || '座位未显示' }} · 席别 {{ ticket.seatType }}
        </span>
        <span class="ticket-amount">
          预计可退 ￥{{ ((ticket.refundableAmount || 0) / 100).toFixed(2) }}
        </span>
      </Checkbox>
    </CheckboxGroup>
    <div class="ticket-actions">
      <span>可多选；选中全部车票时按全部退票处理</span>
      <Button
        danger
        type="primary"
        :loading="submitting"
        :disabled="selectedItemIds.length === 0"
        @click="$emit('submit', selectedItemIds)"
      >
        确认退票范围
      </Button>
    </div>
  </Card>
</template>

<script setup>
import { ref } from 'vue'
import { Alert, Button, Card, Checkbox, CheckboxGroup } from 'ant-design-vue'

defineProps({
  workflow: {
    type: Object,
    required: true
  },
  submitting: {
    type: Boolean,
    default: false
  }
})

defineEmits(['submit'])

const selectedItemIds = ref([])
</script>

<style lang="scss" scoped>
.refund-ticket-card {
  max-width: 680px;
  margin: -12px 0 22px 52px;
}

.ticket-options {
  display: grid;
  gap: 10px;
  width: 100%;
  margin-top: 14px;
}

.ticket-option {
  margin-inline-start: 0;
  padding: 12px;
  border: 1px solid #e8e8e8;
  border-radius: 4px;
}

.ticket-title,
.ticket-detail,
.ticket-amount {
  display: block;
}

.ticket-title {
  color: #262626;
  font-weight: 600;
}

.ticket-detail,
.ticket-amount {
  margin-top: 4px;
  color: #8c8c8c;
  font-size: 12px;
}

.ticket-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 14px;
  color: #8c8c8c;
  font-size: 12px;
}
</style>
