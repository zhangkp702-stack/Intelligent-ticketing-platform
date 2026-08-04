package org.opengoofy.index12306.ai.agentservice.conversation.service.impl;

import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.ConversationDeletionRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.ConversationRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationDeletionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 在用户归属校验后原子清理会话及其关联持久化数据。
 */
@Service
public class ConversationDeletionServiceImpl implements ConversationDeletionService {

    private final ConversationRepository conversationRepository;
    private final ConversationDeletionRepository conversationDeletionRepository;

    /**
     * 创建会话删除服务。
     *
     * @param conversationRepository 会话仓储
     * @param conversationDeletionRepository 关联数据清理仓储
     */
    public ConversationDeletionServiceImpl(
            ConversationRepository conversationRepository,
            ConversationDeletionRepository conversationDeletionRepository) {
        this.conversationRepository = conversationRepository;
        this.conversationDeletionRepository = conversationDeletionRepository;
    }

    /**
     * 删除当前用户拥有的指定会话及其关联数据。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     */
    @Transactional
    @Override
    public void deleteConversation(String userId, String conversationId) {
        requireText(userId, "用户标识不能为空");
        requireText(conversationId, "会话标识不能为空");

        // 先锁定会话并完成归属校验，避免删除过程中与新的消息写入交叉执行。
        ConversationEntity conversation = conversationRepository.findLockedById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        if (!conversation.belongsTo(userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }

        // 按依赖关系先删除关联记录，最后删除会话主记录，确保不会留下孤儿数据。
        conversationDeletionRepository.deleteActionInboxByConversationId(conversationId);
        conversationDeletionRepository.deleteActionOutboxByConversationId(conversationId);
        conversationDeletionRepository.deleteActionReconciliationsByConversationId(conversationId);
        conversationDeletionRepository.deleteActionCommandAuditsByConversationId(conversationId);
        conversationDeletionRepository.deleteActionCommandsByConversationId(conversationId);
        conversationDeletionRepository.deleteLegacyActionExecutionsByConversationId(conversationId);
        conversationDeletionRepository.deleteActionDraftsByConversationId(conversationId);
        conversationDeletionRepository.deleteWorkflowsByConversationId(conversationId);
        conversationDeletionRepository.deleteToolCallsByConversationId(conversationId);
        conversationDeletionRepository.deleteModelCallsByConversationId(conversationId);
        conversationDeletionRepository.deleteContextSnapshotsByConversationId(conversationId);
        conversationDeletionRepository.deleteSummaryTasksByConversationId(conversationId);
        conversationDeletionRepository.deleteSummariesByConversationId(conversationId);
        conversationDeletionRepository.deleteTaskExecutionsByConversationId(conversationId);
        conversationDeletionRepository.deleteStreamEventsByConversationId(conversationId);
        conversationDeletionRepository.deleteTurnsByConversationId(conversationId);
        conversationDeletionRepository.deleteMessagesByConversationId(conversationId);
        conversationRepository.deleteById(conversationId);
    }

    /**
     * 校验必要的文本参数。
     *
     * @param value 参数值
     * @param message 失败说明
     */
    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
