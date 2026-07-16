<template>
  <div class="chat-input">
    <Textarea
      v-model:value="content"
      :maxlength="4000"
      :auto-size="{ minRows: 2, maxRows: 6 }"
      :disabled="disabled"
      placeholder="请输入您的购票、订单或退票问题，Enter 发送，Shift+Enter 换行"
      show-count
      @keydown="handleKeydown"
    />
    <div class="input-footer">
      <span class="input-tip"
        >请勿在对话中发送身份证号、银行卡号等敏感信息</span
      >
      <Space>
        <Button v-if="streaming" danger @click="$emit('stop')">
          停止生成
        </Button>
        <Button
          type="primary"
          :loading="streaming"
          :disabled="disabled || !content.trim()"
          @click="submit"
        >
          发送
        </Button>
      </Space>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { Button, Input, Space } from 'ant-design-vue'

const { TextArea: Textarea } = Input
const content = ref('')

defineProps({
  disabled: Boolean,
  streaming: Boolean
})

const emit = defineEmits(['send', 'stop'])

/**
 * 发送去除首尾空白后的问题，并在成功交给父组件后清空输入框。
 */
const submit = () => {
  const message = content.value.trim()
  if (!message) {
    return
  }
  emit('send', message)
  content.value = ''
}

/**
 * Enter 发送，Shift+Enter 保留浏览器默认换行行为。
 *
 * @param {KeyboardEvent} event 键盘事件
 */
const handleKeydown = (event) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    submit()
  }
}
</script>

<style lang="scss" scoped>
.chat-input {
  padding: 16px;
  background: #fff;
  border-top: 1px solid #e8e8e8;
}

.input-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
}

.input-tip {
  color: #999;
  font-size: 12px;
}
</style>
