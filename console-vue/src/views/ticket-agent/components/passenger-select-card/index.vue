<template>
  <Card class="passenger-card" size="small" title="选择乘车人">
    <Alert type="info" show-icon :message="workflow.prompt" />
    <CheckboxGroup v-model:value="selectedIds" class="passenger-options">
      <Checkbox
        v-for="option in workflow.options"
        :key="option.passengerId"
        :value="option.passengerId"
        class="passenger-option"
      >
        <span class="passenger-name">{{ option.realName }}</span>
        <span class="passenger-id">{{ option.maskedIdCard || '证件信息已保护' }}</span>
      </Checkbox>
    </CheckboxGroup>
    <div class="passenger-actions">
      <span>可选择 {{ workflow.minSelections }}–{{ workflow.maxSelections }} 人</span>
      <Button
        type="primary"
        :loading="submitting"
        :disabled="!selectionValid"
        @click="$emit('submit', selectedIds)"
      >
        确认乘车人
      </Button>
    </div>
  </Card>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Alert, Button, Card, Checkbox, CheckboxGroup } from 'ant-design-vue'

const props = defineProps({
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

const selectedIds = ref([])
const selectionValid = computed(
  () =>
    selectedIds.value.length >= props.workflow.minSelections &&
    selectedIds.value.length <= props.workflow.maxSelections
)
</script>

<style lang="scss" scoped>
.passenger-card {
  max-width: 620px;
  margin: -12px 0 22px 52px;
}

.passenger-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  width: 100%;
  margin-top: 14px;
}

.passenger-option {
  margin-inline-start: 0;
  padding: 10px;
  border: 1px solid #e8e8e8;
  border-radius: 4px;
}

.passenger-name,
.passenger-id {
  display: block;
}

.passenger-name {
  color: #262626;
  font-weight: 600;
}

.passenger-id {
  margin-top: 3px;
  color: #8c8c8c;
  font-size: 12px;
}

.passenger-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 14px;
  color: #8c8c8c;
  font-size: 12px;
}

@media (max-width: 900px) {
  .passenger-options {
    grid-template-columns: 1fr;
  }
}
</style>
