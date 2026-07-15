package org.opengoofy.index12306.ai.agentservice.memory.service;

import org.opengoofy.index12306.ai.agentservice.memory.domain.SummaryTaskEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 在回答事务提交后检查摘要阈值并把任务交给专用线程池。
 */
@Component
@ConditionalOnProperty(
        prefix = "index12306.agent.memory",
        name = "summary-auto-dispatch-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SummaryTriggerListener {

    private final SummaryTaskService taskService;
    private final SummaryTaskDispatcher dispatcher;

    /**
     * 创建回答完成后的摘要触发器。
     *
     * @param taskService 摘要任务状态服务
     * @param dispatcher 摘要异步调度器
     */
    public SummaryTriggerListener(
            SummaryTaskService taskService,
            SummaryTaskDispatcher dispatcher) {
        this.taskService = taskService;
        this.dispatcher = dispatcher;
    }

    /**
     * 仅在回答事务成功提交后创建并异步调度满足阈值的摘要任务。
     *
     * @param event 已提交回答的最小定位信息
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTurnCompleted(TurnCompletedEvent event) {
        // 摘要任务只接收数据库任务标识，异步线程随后从数据库恢复完整工作输入。
        taskService.enqueueIfNeeded(
                        event.userId(),
                        event.conversationId(),
                        event.topicId(),
                        event.throughSequence())
                .map(SummaryTaskEntity::getId)
                .ifPresent(dispatcher::dispatch);
    }
}
