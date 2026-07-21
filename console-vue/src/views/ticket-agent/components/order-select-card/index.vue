<template>
  <Card class="order-card" size="small" title="选择要取消的订单">
    <Alert type="warning" show-icon :message="workflow.prompt" />
    <RadioGroup v-model:value="selectedOrderSn" class="order-options">
      <Radio
        v-for="order in workflow.orders"
        :key="order.orderSn"
        :value="order.orderSn"
        class="order-option"
      >
        <span class="order-title">
          {{ order.trainNumber || '未知车次' }}
          {{ order.departure }} → {{ order.arrival }}
        </span>
        <span class="order-detail">
          {{ order.ridingDate }} {{ order.departureTime }} ·
          {{ order.realName || '乘车人未显示' }}
        </span>
        <span class="order-number">订单号：{{ order.orderSn }}</span>
      </Radio>
    </RadioGroup>
    <div class="order-actions">
      <span>这里只生成取消草案，仍需在确认卡片中确认</span>
      <Button
        danger
        type="primary"
        :loading="submitting"
        :disabled="!selectedOrderSn"
        @click="$emit('submit', selectedOrderSn)"
      >
        选择此订单
      </Button>
    </div>
  </Card>
</template>

<script setup>
import { ref } from 'vue'
import { Alert, Button, Card, Radio, RadioGroup } from 'ant-design-vue'

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

const selectedOrderSn = ref(null)
</script>

<style lang="scss" scoped>
.order-card {
  max-width: 680px;
  margin: -12px 0 22px 52px;
}

.order-options {
  display: grid;
  gap: 10px;
  width: 100%;
  margin-top: 14px;
}

.order-option {
  margin-inline-start: 0;
  padding: 12px;
  border: 1px solid #e8e8e8;
  border-radius: 4px;
}

.order-title,
.order-detail,
.order-number {
  display: block;
}

.order-title {
  color: #262626;
  font-weight: 600;
}

.order-detail,
.order-number {
  margin-top: 4px;
  color: #8c8c8c;
  font-size: 12px;
}

.order-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 14px;
  color: #8c8c8c;
  font-size: 12px;
}
</style>
