package org.opengoofy.index12306.ai.agentservice.conversation;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.ConversationRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.ConversationSummaryRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.MessageRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.SummaryTaskRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.TurnRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationDeletionService;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationMemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * 验证用户会话删除的归属校验和关联数据清理。
 */
@ActiveProfiles("test")
@SpringBootTest
class ConversationDeletionServiceTests {

    @Autowired
    private ConversationMemoryService conversationMemoryService;

    @Autowired
    private ConversationDeletionService conversationDeletionService;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private TurnRepository turnRepository;

    @Autowired
    private ConversationSummaryRepository conversationSummaryRepository;

    @Autowired
    private SummaryTaskRepository summaryTaskRepository;

    /**
     * 删除会话时清理消息、轮次、摘要任务和摘要内容。
     */
    @Test
    void deleteConversationRemovesOwnedConversationAndRelatedMemory() {
        String userId = unique("user");
        ConversationEntity conversation = conversationMemoryService.createConversation(userId, "待删除会话");
        // 使用服务端签发的轮次凭证写入待删除数据。
        ConversationMemoryService.PreparedTurn preparedTurn = conversationMemoryService.prepareTurn(
                userId, conversation.getId());
        ConversationMemoryService.StartedTurn started = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId, conversation.getId(), preparedTurn.turnId(),
                        preparedTurn.submissionToken(), "alice", "查询北京到上海", 8));
        conversationMemoryService.completeTurn(
                new ConversationMemoryService.CompleteTurnCommand(
                        userId, started.turnId(), "已找到车次", 8,
                        started.executionOwner(), started.fencingToken()));
        conversationDeletionService.deleteConversation(userId, conversation.getId());

        assertThat(conversationRepository.selectById(conversation.getId())).isNull();
        assertThat(messageRepository.findByConversationIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                conversation.getId(), 0)).isEmpty();
        assertThat(turnRepository.findByConversationIdAndRequestId(
                conversation.getId(), started.turnId())).isEmpty();
        assertThat(conversationSummaryRepository.findByConversationId(conversation.getId())).isEmpty();
        assertThat(summaryTaskRepository.findByConversationId(conversation.getId())).isEmpty();
    }

    /**
     * 删除请求的用户不是会话所有者时保留原会话。
     */
    @Test
    void deleteConversationRejectsAnotherUser() {
        String ownerId = unique("owner");
        ConversationEntity conversation = conversationMemoryService.createConversation(ownerId, "私有会话");

        assertThatIllegalArgumentException().isThrownBy(() ->
                conversationDeletionService.deleteConversation(unique("other"), conversation.getId()));

        assertThat(conversationRepository.selectById(conversation.getId())).isNotNull();
    }

    /**
     * 生成符合数据库长度限制的唯一测试标识。
     *
     * @param prefix 可读前缀
     * @return 唯一标识
     */
    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }
}
