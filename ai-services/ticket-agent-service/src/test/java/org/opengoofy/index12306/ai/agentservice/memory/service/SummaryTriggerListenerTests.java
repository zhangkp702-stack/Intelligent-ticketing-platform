package org.opengoofy.index12306.ai.agentservice.memory.service;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.memory.domain.SummaryTaskEntity;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证回答提交事件只调度满足摘要阈值的持久化任务。
 */
class SummaryTriggerListenerTests {

    /**
     * 验证任务服务返回持久化任务后，监听器仅向异步执行器传递任务标识。
     */
    @Test
    void dispatchesPersistedTaskId() {
        SummaryTaskService taskService = mock(SummaryTaskService.class);
        SummaryTaskDispatcher dispatcher = mock(SummaryTaskDispatcher.class);
        SummaryTaskEntity task = SummaryTaskEntity.pending(
                "conversation-1", "topic-1", 1, 12, 1, 3, Instant.now());
        when(taskService.enqueueIfNeeded("user-1", "conversation-1", "topic-1", 12))
                .thenReturn(Optional.of(task));
        SummaryTriggerListener listener = new SummaryTriggerListener(taskService, dispatcher);

        // 监听器收到的事件不携带消息正文，异步边界只传递数据库任务标识。
        listener.onTurnCompleted(new TurnCompletedEvent(
                "user-1", "conversation-1", "topic-1", 12));

        verify(dispatcher).dispatch(task.getId());
    }

    /**
     * 验证未达到摘要阈值时不会占用异步线程池。
     */
    @Test
    void skipsDispatchWhenThresholdIsNotReached() {
        SummaryTaskService taskService = mock(SummaryTaskService.class);
        SummaryTaskDispatcher dispatcher = mock(SummaryTaskDispatcher.class);
        when(taskService.enqueueIfNeeded("user-1", "conversation-1", "topic-1", 2))
                .thenReturn(Optional.empty());
        SummaryTriggerListener listener = new SummaryTriggerListener(taskService, dispatcher);

        // 没有持久化任务时监听器直接结束，不创建空异步工作。
        listener.onTurnCompleted(new TurnCompletedEvent(
                "user-1", "conversation-1", "topic-1", 2));

        verify(dispatcher, never()).dispatch(org.mockito.ArgumentMatchers.anyString());
    }
}
