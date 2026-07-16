<template>
  <Card title="智能购票" :bordered="false" class="conversation-panel">
    <Button
      type="primary"
      block
      :loading="creating"
      :disabled="streaming"
      @click="$emit('create')"
    >
      <PlusOutlined />
      新建会话
    </Button>
    <div class="conversation-label">当前会话</div>
    <div class="conversation-item" :class="{ empty: !conversationId }">
      <MessageOutlined />
      <span>{{ conversationId ? title : '尚未创建会话' }}</span>
    </div>
    <Alert
      class="conversation-tip"
      type="info"
      show-icon
      message="当前版本说明"
      description="本阶段保存当前页面会话，历史会话列表将在后端查询接口完成后接入。"
    />
  </Card>
</template>

<script setup>
import { Alert, Button, Card } from 'ant-design-vue'
import { MessageOutlined, PlusOutlined } from '@ant-design/icons-vue'

defineProps({
  conversationId: String,
  title: {
    type: String,
    default: '当前智能购票会话'
  },
  creating: Boolean,
  streaming: Boolean
})

defineEmits(['create'])
</script>

<style lang="scss" scoped>
.conversation-panel {
  height: 100%;
}

.conversation-label {
  margin: 22px 0 8px;
  color: #8f9598;
  font-size: 13px;
}

.conversation-item {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 12px;
  color: #1e71bd;
  background: #f2f7fc;
  border-left: 3px solid #1e71bd;

  &.empty {
    color: #999;
    background: #f7f7f7;
    border-left-color: #d9d9d9;
  }

  span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.conversation-tip {
  margin-top: 20px;
}
</style>
