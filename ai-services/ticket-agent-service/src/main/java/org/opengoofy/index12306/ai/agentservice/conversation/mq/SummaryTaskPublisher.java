package org.opengoofy.index12306.ai.agentservice.conversation.mq;

import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.opengoofy.index12306.ai.agentservice.conversation.service.SummaryTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 扫描数据库 Outbox 状态并在在线请求之外可靠发布摘要任务。
 */
@Component
@ConditionalOnProperty(
        prefix = "index12306.agent.memory.mq",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SummaryTaskPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(SummaryTaskPublisher.class);

    private final SummaryTaskService taskService;
    private final RocketMQTemplate rocketMQTemplate;
    private final Clock clock;
    private final String destination;
    private final long sendTimeoutMillis;

    /**
     * 创建摘要任务 Outbox 发布器。
     *
     * @param taskService 摘要任务状态服务
     * @param rocketMQTemplate RocketMQ 客户端
     * @param clock 统一时钟
     * @param topic MQ 主题
     * @param tag MQ 标签
     * @param sendTimeoutMillis 单次发送超时毫秒数
     */
    public SummaryTaskPublisher(
            SummaryTaskService taskService,
            RocketMQTemplate rocketMQTemplate,
            Clock clock,
            @Value("${index12306.agent.memory.mq.topic:index12306_agent_memory_summary_topic}") String topic,
            @Value("${index12306.agent.memory.mq.tag:GENERATE}") String tag,
            @Value("${index12306.agent.memory.mq.send-timeout-millis:2000}") long sendTimeoutMillis) {
        this.taskService = taskService;
        this.rocketMQTemplate = rocketMQTemplate;
        this.clock = clock;
        this.destination = topic + ":" + tag;
        this.sendTimeoutMillis = sendTimeoutMillis;
    }

    /**
     * 定时发布有限批次的待发送任务，发送失败时保留 PENDING 供下轮恢复。
     */
    @Scheduled(fixedDelayString = "${index12306.agent.memory.mq.publish-interval-millis:1000}")
    public void publishPending() {
        // 发布前恢复消费者宕机或异常窗口遗留的任务，避免任务永久停在运行或重试状态。
        int recovered = taskService.recoverExpiredTasks();
        if (recovered > 0) {
            LOGGER.warn("恢复过期摘要任务，count={}", recovered);
        }
        // 同步确认只发生在后台调度线程，用于明确 MQ 是否已经持久化消息。
        for (SummaryTaskService.PendingTask task : taskService.pendingTasks()) {
            try {
                SummaryTaskMessage payload = new SummaryTaskMessage(
                        task.taskId(), task.conversationId(), task.eventVersion(),
                        task.throughSequence(), task.expectedSummaryVersion(), clock.instant());
                Message<SummaryTaskMessage> message = MessageBuilder.withPayload(payload)
                        .setHeader(MessageConst.PROPERTY_KEYS, task.conversationId() + ":" + task.eventVersion())
                        .build();
                SendResult result = rocketMQTemplate.syncSend(destination, message, sendTimeoutMillis);
                taskService.markPublished(task.taskId(), task.eventVersion(), result.getMsgId());
                LOGGER.info("摘要任务MQ发布成功，taskId={}, conversationId={}, eventVersion={}, messageId={}",
                        task.taskId(), task.conversationId(), task.eventVersion(), result.getMsgId());
            } catch (RuntimeException ex) {
                // 任务仍为 PENDING，后续调度会再次发布；重复消息由事件版本和任务锁过滤。
                LOGGER.warn("摘要任务MQ发布失败，taskId={}, conversationId={}, eventVersion={}, exceptionType={}",
                        task.taskId(), task.conversationId(), task.eventVersion(), ex.getClass().getSimpleName());
            }
        }
    }
}
