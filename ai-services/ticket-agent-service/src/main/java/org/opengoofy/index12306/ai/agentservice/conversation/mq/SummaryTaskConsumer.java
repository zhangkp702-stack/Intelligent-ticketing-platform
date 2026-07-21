package org.opengoofy.index12306.ai.agentservice.conversation.mq;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.opengoofy.index12306.ai.agentservice.conversation.service.SummaryTaskProcessor;
import org.opengoofy.index12306.ai.agentservice.conversation.service.SummaryTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 从 RocketMQ 领取会话摘要任务并在请求链路之外调用摘要模型。
 */
@Component
@ConditionalOnProperty(
        prefix = "index12306.agent.memory.mq",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RocketMQMessageListener(
        topic = "${index12306.agent.memory.mq.topic:index12306_agent_memory_summary_topic}",
        selectorExpression = "${index12306.agent.memory.mq.tag:GENERATE}",
        consumerGroup = "${index12306.agent.memory.mq.consumer-group:index12306_agent_memory_summary_cg}")
public class SummaryTaskConsumer implements RocketMQListener<SummaryTaskMessage> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SummaryTaskConsumer.class);

    private final SummaryTaskService taskService;
    private final SummaryTaskProcessor processor;

    /**
     * 创建摘要任务消费者。
     *
     * @param taskService 摘要任务状态服务
     * @param processor 摘要模型处理器
     */
    public SummaryTaskConsumer(SummaryTaskService taskService, SummaryTaskProcessor processor) {
        this.taskService = taskService;
        this.processor = processor;
    }

    /**
     * 幂等处理摘要事件；可重试失败通过抛出异常交由 RocketMQ 重投。
     *
     * @param message 摘要任务定位消息
     */
    @Override
    public void onMessage(SummaryTaskMessage message) {
        String workerId = "summary-mq-" + UUID.randomUUID().toString().substring(0, 8);
        Optional<SummaryTaskService.SummaryWorkItem> claimed = taskService.claim(
                message.taskId(), message.eventVersion(), workerId);
        if (claimed.isEmpty()) {
            LOGGER.info("忽略重复或过期摘要消息，taskId={}, conversationId={}, eventVersion={}",
                    message.taskId(), message.conversationId(), message.eventVersion());
            return;
        }

        long startedNanos = System.nanoTime();
        try {
            // 模型调用在领取事务提交后执行，不持有任务或摘要行锁。
            SummaryTaskService.SummaryGenerationResult result = processor.process(claimed.get());
            taskService.complete(message.taskId(), result);
            LOGGER.info("摘要任务消费成功，taskId={}, conversationId={}, eventVersion={}, durationMs={}",
                    message.taskId(), message.conversationId(), message.eventVersion(),
                    (System.nanoTime() - startedNanos) / 1_000_000);
        } catch (Exception ex) {
            boolean retry = false;
            try {
                retry = taskService.fail(
                        message.taskId(), ex.getClass().getSimpleName(), ex.getMessage());
            } catch (RuntimeException stateException) {
                LOGGER.warn("摘要任务失败状态记录异常，taskId={}, exceptionType={}",
                        message.taskId(), stateException.getClass().getSimpleName());
            }
            LOGGER.warn("摘要任务消费失败，taskId={}, conversationId={}, eventVersion={}, retry={}, exceptionType={}",
                    message.taskId(), message.conversationId(), message.eventVersion(), retry,
                    ex.getClass().getSimpleName());
            if (retry) {
                throw ex instanceof RuntimeException runtimeException
                        ? runtimeException : new IllegalStateException("摘要模型处理失败", ex);
            }
        }
    }
}
