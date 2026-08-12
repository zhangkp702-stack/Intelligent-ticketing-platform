package org.opengoofy.index12306.ai.agentservice.conversation.context;

import org.opengoofy.index12306.ai.agentservice.conversation.service.SummaryTaskProcessor;
import org.opengoofy.index12306.ai.agentservice.conversation.service.SummaryTaskService;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * 在上下文加载前同步补齐已经冻结的摘要批次。
 * <p>
 * 摘要任务被其他消费者占用、失败重试或积压时，不再因为未覆盖轮次超过最近窗口而拒绝用户请求；
 * 上下文加载器会在摘要仍落后且未超过回退上限时读取全部未覆盖终态轮次。
 */
@Service
public class ConversationSummaryBarrier {

    private final SummaryTaskService summaryTaskService;
    private final SummaryTaskProcessor summaryTaskProcessor;

    /**
     * 创建会话摘要兜底服务。
     *
     * @param summaryTaskService 摘要任务状态服务
     * @param summaryTaskProcessor 摘要模型处理器
     */
    public ConversationSummaryBarrier(
            SummaryTaskService summaryTaskService,
            SummaryTaskProcessor summaryTaskProcessor) {
        this.summaryTaskService = summaryTaskService;
        this.summaryTaskProcessor = summaryTaskProcessor;
    }

    /**
     * 确保当前问题之前已经排队的摘要批次优先完成。
     * <p>
     * 如果摘要仍未完成，调用方会在回退上限内读取摘要边界之后的全部终态轮次，保证历史完整。
     *
     * @param conversationId 会话标识
     * @param currentUserSequence 当前用户消息序号
     */
    public void ensureCoveredBeforeCurrentQuestion(
            String conversationId,
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
                // 失败仍写回可重试任务状态；当前请求随后在回退上限内加载未覆盖轮次。
                summaryTaskService.fail(
                        workItem.get().taskId(),
                        exception.getClass().getSimpleName(),
                        exception.getMessage());
            }
        }

        // 摘要被其他 MQ 消费者占用或仍处于重试状态时，不阻断本轮；加载器负责按回退上限选择历史范围。
    }

}
