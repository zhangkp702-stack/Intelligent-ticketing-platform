package org.opengoofy.index12306.ai.agentservice.conversation.service;

import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationSummaryEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.SummaryTaskEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageRole;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageType;

import java.util.List;
import java.util.Optional;

/**
 * 定义会话摘要任务的合并、发布、领取和结果提交能力。
 */
public interface SummaryTaskService {

    /**
     * 检查未摘要消息数量并合并摘要目标边界。
     *
     * @param conversationId 会话标识
     * @param throughSequence 当前助手消息序号
     * @return 达到阈值时返回摘要任务
     */
    Optional<SummaryTaskEntity> requestIfNeeded(String conversationId, long throughSequence);

    /**
     * 查询等待发布的摘要任务。
     *
     * @return 最多一百个待发布任务
     */
    List<PendingTask> pendingTasks();

    /**
     * 恢复执行租约过期或达到重试时间的摘要任务。
     *
     * @return 恢复任务数量
     */
    int recoverExpiredTasks();

    /**
     * 标记指定事件版本已经发布到消息队列。
     *
     * @param taskId 任务标识
     * @param eventVersion 事件版本
     * @param messageId 消息标识
     */
    void markPublished(String taskId, long eventVersion, String messageId);

    /**
     * 领取摘要任务并构造脱离持久化上下文的工作项。
     *
     * @param taskId 任务标识
     * @param eventVersion 事件版本
     * @param workerId 工作节点标识
     * @return 成功领取后的工作项
     */
    Optional<SummaryWorkItem> claim(String taskId, long eventVersion, String workerId);

    /**
     * 提交新的完整摘要版本并完成任务。
     *
     * @param taskId 任务标识
     * @param result 摘要模型生成结果
     * @return 最新会话摘要
     */
    ConversationSummaryEntity complete(String taskId, SummaryGenerationResult result);

    /**
     * 记录摘要任务失败并按配置安排重试。
     *
     * @param taskId 任务标识
     * @param category 稳定失败分类
     * @param safeMessage 脱敏失败信息
     * @return 任务状态发生变化时返回 true
     */
    boolean fail(String taskId, String category, String safeMessage);

    /**
     * 待发布摘要任务的最小消息数据。
     *
     * @param taskId 任务标识
     * @param conversationId 会话标识
     * @param eventVersion 事件版本
     * @param throughSequence 目标消息边界
     * @param expectedSummaryVersion 预期摘要版本
     */
    record PendingTask(
            String taskId,
            String conversationId,
            long eventVersion,
            long throughSequence,
            int expectedSummaryVersion) {
    }

    /**
     * 摘要模型使用的不可变工作输入。
     *
     * @param taskId 任务标识
     * @param conversationId 会话标识
     * @param eventVersion 事件版本
     * @param expectedSummaryVersion 预期摘要版本
     * @param throughSequence 本次冻结的消息边界
     * @param previousSummary 上一份完整摘要
     * @param previousStructuredState 上一份结构化状态
     * @param messages 本次新增原始消息
     */
    record SummaryWorkItem(
            String taskId,
            String conversationId,
            long eventVersion,
            int expectedSummaryVersion,
            long throughSequence,
            String previousSummary,
            String previousStructuredState,
            List<SummarySourceMessage> messages) {
    }

    /**
     * 摘要来源消息。
     *
     * @param messageId 消息标识
     * @param sequenceNo 消息序号
     * @param role 消息角色
     * @param messageType 消息类型
     * @param content 原始正文
     * @param tokenCount Token 估算
     */
    record SummarySourceMessage(
            String messageId,
            long sequenceNo,
            MessageRole role,
            MessageType messageType,
            String content,
            int tokenCount) {
    }

    /**
     * 摘要模型生成结果。
     *
     * @param summaryContent 新的完整累计摘要
     * @param structuredState 新的结构化状态
     * @param providerId 实际模型平台
     * @param candidateId 实际候选模型
     * @param modelId 实际模型标识
     */
    record SummaryGenerationResult(
            String summaryContent,
            String structuredState,
            String providerId,
            String candidateId,
            String modelId) {
    }
}
