package org.opengoofy.index12306.ai.agentservice.conversation.context;

import org.opengoofy.index12306.ai.agentservice.conversation.config.AgentMemoryProperties;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationSummaryEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.TurnEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.ConversationSummaryRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.TurnRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.service.SummaryTaskProcessor;
import org.opengoofy.index12306.ai.agentservice.conversation.service.SummaryTaskService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 在最近轮次窗口不足以覆盖完整历史时，同步补齐已经冻结的摘要批次。
 */
@Service
public class ConversationSummaryBarrier {

    private final AgentMemoryProperties properties;
    private final ConversationSummaryRepository summaryRepository;
    private final TurnRepository turnRepository;
    private final SummaryTaskService summaryTaskService;
    private final SummaryTaskProcessor summaryTaskProcessor;

    /**
     * 创建会话摘要兜底服务。
     *
     * @param properties 上下文窗口配置
     * @param summaryRepository 会话摘要仓储
     * @param turnRepository 终态轮次仓储
     * @param summaryTaskService 摘要任务状态服务
     * @param summaryTaskProcessor 摘要模型处理器
     */
    public ConversationSummaryBarrier(
            AgentMemoryProperties properties,
            ConversationSummaryRepository summaryRepository,
            TurnRepository turnRepository,
            SummaryTaskService summaryTaskService,
            SummaryTaskProcessor summaryTaskProcessor) {
        this.properties = properties;
        this.summaryRepository = summaryRepository;
        this.turnRepository = turnRepository;
        this.summaryTaskService = summaryTaskService;
        this.summaryTaskProcessor = summaryTaskProcessor;
    }

    /**
     * 确保当前问题之前的历史要么已经摘要覆盖，要么能完整装入最近轮次窗口。
     *
     * @param conversationId 会话标识
     * @param currentTurnId 当前执行轮次标识
     * @param currentUserSequence 当前用户消息序号
     */
    public void ensureCoveredBeforeCurrentQuestion(
            String conversationId,
            String currentTurnId,
            long currentUserSequence) {
        // 仅处理严格早于当前问题的冻结批次，避免摘要中包含当前问题后又作为独立问题重复注入。
        Optional<SummaryTaskService.SummaryWorkItem> workItem = summaryTaskService.claimForContext(
                conversationId,
                currentUserSequence,
                "summary-context-" + UUID.randomUUID().toString().substring(0, 8));
        if (workItem.isPresent()) {
            try {
                // 同步调用只发生在 MQ 尚未消费且历史窗口已经需要该摘要时，不影响正常异步路径。
                SummaryTaskService.SummaryGenerationResult result = summaryTaskProcessor.process(workItem.get());
                summaryTaskService.complete(workItem.get().taskId(), result);
            } catch (Exception exception) {
                // 失败仍写回可重试任务状态，并拒绝使用不完整历史继续规划。
                summaryTaskService.fail(
                        workItem.get().taskId(),
                        exception.getClass().getSimpleName(),
                        exception.getMessage());
                throw new ConversationHistoryUnavailableException("会话历史摘要暂不可用，请稍后重试", exception);
            }
        }

        // 摘要被其他 MQ 消费者占用时不猜测遗漏内容；超过窗口则让本轮失败并等待可重试的摘要完成。
        ConversationSummaryEntity summary = summaryRepository.findByConversationId(conversationId).orElse(null);
        long summarizedThrough = summary == null ? 0 : summary.getSummarizedThroughSequence();
        List<TurnEntity> uncoveredTurns = turnRepository.findRecentTerminalTurns(
                conversationId,
                summarizedThrough,
                currentTurnId,
                properties.recentTurnLimit() + 1);
        if (uncoveredTurns.size() > properties.recentTurnLimit()) {
            throw new ConversationHistoryUnavailableException("会话历史正在整理，请稍后重试");
        }
    }

    /**
     * 表示历史尚未被可靠覆盖，调用方不能在缺少旧消息的条件下继续意图规划。
     */
    public static class ConversationHistoryUnavailableException extends IllegalStateException {

        /**
         * 创建不带底层异常的历史覆盖异常。
         *
         * @param message 用户可读的安全说明
         */
        public ConversationHistoryUnavailableException(String message) {
            super(message);
        }

        /**
         * 创建带底层原因的历史覆盖异常。
         *
         * @param message 用户可读的安全说明
         * @param cause 摘要生成失败原因
         */
        public ConversationHistoryUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
