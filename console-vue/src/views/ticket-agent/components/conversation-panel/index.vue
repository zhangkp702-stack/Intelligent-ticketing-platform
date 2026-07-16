<template>
  <Card title="智能购票" :bordered="false" class="conversation-panel">
    <Button type="primary" block :loading="creating" @click="$emit('create')">
      <PlusOutlined />
      新建会话
    </Button>
    <div class="conversation-label">历史会话</div>
    <div v-if="loading && !conversations.length" class="conversation-loading">
      <Spin size="small" />
      <span>正在加载会话</span>
    </div>
    <Empty
      v-else-if="!conversations.length"
      class="conversation-empty"
      :image="Empty.PRESENTED_IMAGE_SIMPLE"
      description="暂无智能购票会话"
    />
    <div v-else class="conversation-list">
      <button
        v-for="conversation in conversations"
        :key="conversation.conversationId"
        type="button"
        class="conversation-item"
        :class="{
          active: conversation.conversationId === conversationId
        }"
        @click="$emit('select', conversation.conversationId)"
      >
        <MessageOutlined />
        <span class="conversation-content">
          <span class="conversation-title">
            {{ conversation.title || '智能购票会话' }}
          </span>
          <span class="conversation-time">
            {{ formatConversationTime(conversation.updatedAt) }}
          </span>
        </span>
      </button>
      <Button
        v-if="hasMore"
        class="load-more"
        type="link"
        block
        :loading="loadingMore"
        @click="$emit('load-more')"
      >
        加载更多会话
      </Button>
    </div>
  </Card>
</template>

<script setup>
import { Button, Card, Empty, Spin } from 'ant-design-vue'
import { MessageOutlined, PlusOutlined } from '@ant-design/icons-vue'
import dayjs from 'dayjs'

defineProps({
  conversationId: String,
  conversations: {
    type: Array,
    default: () => []
  },
  creating: Boolean,
  loading: Boolean,
  loadingMore: Boolean,
  hasMore: Boolean
})

defineEmits(['create', 'select', 'load-more'])

/**
 * 把会话更新时间转换为紧凑的侧栏展示文本。
 *
 * @param {string} value 后端返回的更新时间
 * @returns {string} 当天时间或年月日
 */
const formatConversationTime = (value) => {
  if (!value) {
    return ''
  }
  const time = dayjs(value)
  return time.isSame(dayjs(), 'day')
    ? time.format('HH:mm')
    : time.format('YYYY-MM-DD')
}
</script>

<style lang="scss" scoped>
.conversation-panel {
  height: 100%;

  :deep(.ant-card-body) {
    display: flex;
    flex-direction: column;
    height: calc(100% - 57px);
  }
}

.conversation-label {
  margin: 22px 0 8px;
  color: #8f9598;
  font-size: 13px;
}

.conversation-loading {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: center;
  padding: 28px 0;
  color: #8f9598;
}

.conversation-empty {
  margin-top: 28px;
}

.conversation-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.conversation-item {
  width: 100%;
  margin-bottom: 8px;
  border: 0;
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 12px;
  color: #666;
  text-align: left;
  background: #fff;
  border-left: 3px solid transparent;
  cursor: pointer;
  transition: background 0.2s;

  &:hover {
    background: #f7f8fa;
  }

  &.active {
    color: #1e71bd;
    background: #f2f7fc;
    border-left-color: #1e71bd;
  }
}

.conversation-content {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
}

.conversation-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-time {
  margin-top: 3px;
  color: #aaa;
  font-size: 11px;
}

.load-more {
  margin-top: 4px;
}
</style>
