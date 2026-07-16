<template>
  <div class="message-row" :class="message.role.toLowerCase()">
    <Avatar
      v-if="message.role === 'ASSISTANT'"
      class="message-avatar assistant-avatar"
    >
      智
    </Avatar>
    <div class="message-content">
      <div class="message-name">
        {{ message.role === 'USER' ? '我' : '智能购票助手' }}
      </div>
      <div class="message-bubble" :class="{ error: message.error }">
        <span v-if="message.content">{{ message.content }}</span>
        <span v-else-if="message.pending" class="typing-text">正在思考</span>
        <span v-else>未返回有效内容</span>
      </div>
      <Alert
        v-if="message.error"
        class="message-error"
        type="error"
        show-icon
        :message="message.error"
        :description="message.failureCategory || undefined"
      />
    </div>
    <Avatar v-if="message.role === 'USER'" class="message-avatar user-avatar">
      我
    </Avatar>
  </div>
</template>

<script setup>
import { Alert, Avatar } from 'ant-design-vue'

defineProps({
  message: {
    type: Object,
    required: true
  }
})
</script>

<style lang="scss" scoped>
.message-row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 22px;

  &.user {
    justify-content: flex-end;
  }
}

.message-avatar {
  flex: 0 0 auto;
}

.assistant-avatar {
  background: #1e71bd;
}

.user-avatar {
  background: #64a0f6;
}

.message-content {
  max-width: 75%;
}

.user .message-content {
  text-align: right;
}

.message-name {
  margin-bottom: 5px;
  color: #8f9598;
  font-size: 12px;
}

.message-bubble {
  padding: 12px 16px;
  color: #333;
  line-height: 1.8;
  text-align: left;
  white-space: pre-wrap;
  word-break: break-word;
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 4px;

  &.error {
    border-color: #ffccc7;
  }
}

.user .message-bubble {
  color: #fff;
  background: #1e71bd;
  border-color: #1e71bd;
}

.typing-text::after {
  content: '...';
  display: inline-block;
  width: 20px;
  overflow: hidden;
  vertical-align: bottom;
  animation: typing 1.2s steps(4, end) infinite;
}

.message-error {
  margin-top: 8px;
  text-align: left;
}

@keyframes typing {
  from {
    width: 0;
  }

  to {
    width: 20px;
  }
}
</style>
