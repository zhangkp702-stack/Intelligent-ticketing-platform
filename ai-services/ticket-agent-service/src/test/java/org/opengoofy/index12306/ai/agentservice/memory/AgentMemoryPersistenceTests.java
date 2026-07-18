package org.opengoofy.index12306.ai.agentservice.memory;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ConversationSummaryEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageRole;
import org.opengoofy.index12306.ai.agentservice.memory.domain.SummaryTaskEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.SummaryTaskStatus;
import org.opengoofy.index12306.ai.agentservice.memory.domain.TurnEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.TurnStatus;
import org.opengoofy.index12306.ai.agentservice.memory.repository.ContextSnapshotRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.ConversationSummaryRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.SummaryTaskRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.TurnRepository;
import org.opengoofy.index12306.ai.agentservice.memory.service.ConversationContextService;
import org.opengoofy.index12306.ai.agentservice.memory.service.ConversationMemoryService;
import org.opengoofy.index12306.ai.agentservice.memory.service.SummaryTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证会话消息、唯一摘要和会话级上下文在真实 JPA 映射下的核心约束。
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "index12306.agent.memory.summary-trigger-message-count=2")
class AgentMemoryPersistenceTests {

    @Autowired
    private ConversationMemoryService conversationMemoryService;

    @Autowired
    private ConversationContextService conversationContextService;

    @Autowired
    private SummaryTaskService summaryTaskService;

    @Autowired
    private TurnRepository turnRepository;

    @Autowired
    private SummaryTaskRepository summaryTaskRepository;

    @Autowired
    private ConversationSummaryRepository summaryRepository;

    @Autowired
    private ContextSnapshotRepository snapshotRepository;

    /**
     * 验证同一请求不会重复创建轮次或消息，用户与助手消息序号严格递增。
     */
    @Test
    void conversationTurnIsIdempotentAndOrdered() {
        Fixture fixture = createCompletedTurn("查询明天北京到上海的票", "明天有多趟列车可选");

        // 使用相同请求标识重试时，应复用第一次写入的轮次和消息。
        ConversationMemoryService.StartedTurn retried = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        fixture.userId(), fixture.conversationId(), fixture.requestId(),
                        fixture.requestId(), "查询明天北京到上海的票", 10));
        MessageEntity completedAgain = conversationMemoryService.completeTurn(
                new ConversationMemoryService.CompleteTurnCommand(
                        fixture.userId(), fixture.turnId(), "不会重复写入的回答", 8));

        TurnEntity turn = turnRepository.findById(fixture.turnId()).orElseThrow();
        assertThat(retried.created()).isFalse();
        assertThat(retried.turnId()).isEqualTo(fixture.turnId());
        assertThat(retried.sequenceNo()).isEqualTo(1L);
        assertThat(completedAgain.getId()).isEqualTo(fixture.assistantMessageId());
        assertThat(completedAgain.getSequenceNo()).isEqualTo(2L);
        assertThat(turn.getStatus()).isEqualTo(TurnStatus.COMPLETED);
    }

    /**
     * 验证回答上下文直接加载会话摘要边界后的消息，不再执行主题判断。
     */
    @Test
    void conversationContextLoadsMessagesAndPersistsSnapshot() {
        Fixture fixture = createCompletedTurn("查询北京到上海的余票", "已有可选车次");
        String requestId = unique("context");

        // 新问题写入后直接按会话装配上下文，当前问题也属于摘要边界后的原始消息。
        conversationMemoryService.startTurn(new ConversationMemoryService.StartTurnCommand(
                fixture.userId(), fixture.conversationId(), requestId, requestId, "二等座还有吗", 6));
        ConversationContextService.ConversationContext context = conversationContextService.load(
                fixture.userId(), requestId, fixture.conversationId());

        assertThat(context.messages()).extracting(ConversationContextService.ContextMessage::role)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER);
        assertThat(context.messages()).extracting(ConversationContextService.ContextMessage::content)
                .containsExactly("查询北京到上海的余票", "已有可选车次", "二等座还有吗");
        assertThat(snapshotRepository.findByRequestId(requestId)).isPresent();
    }

    /**
     * 验证每个会话只保留一个任务和一份摘要，摘要成功后原地推进版本与边界。
     */
    @Test
    void summaryTaskCommitsSingleConversationSummary() {
        Fixture fixture = createCompletedTurn("查询后天去杭州的车票", "已经找到可选车次");
        SummaryTaskEntity task = summaryTaskRepository.findByConversationId(fixture.conversationId())
                .orElseThrow();

        // 重复请求相同边界只复用会话唯一任务行，MQ 重复消息也由事件版本过滤。
        SummaryTaskEntity retried = summaryTaskService
                .requestIfNeeded(fixture.conversationId(), 2L)
                .orElseThrow();
        assertThat(retried.getId()).isEqualTo(task.getId());
        SummaryTaskService.SummaryWorkItem workItem = summaryTaskService
                .claim(task.getId(), task.getEventVersion(), "test-worker")
                .orElseThrow();
        ConversationSummaryEntity summary = summaryTaskService.complete(
                task.getId(),
                new SummaryTaskService.SummaryGenerationResult(
                        "用户查询后天去杭州，系统已找到可选车次。",
                        "{\"intent\":\"ticket_query\"}",
                        "siliconflow", "summary-primary", "Qwen/Qwen3.5-9B"));

        SummaryTaskEntity completed = summaryTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(workItem.messages()).hasSize(2);
        assertThat(completed.getStatus()).isEqualTo(SummaryTaskStatus.SUCCEEDED);
        assertThat(summary.getSummaryVersion()).isEqualTo(1);
        assertThat(summary.getSummarizedThroughSequence()).isEqualTo(2L);
        ConversationSummaryEntity persisted = summaryRepository.findByConversationId(fixture.conversationId())
                .orElseThrow();
        assertThat(persisted.getId()).isEqualTo(summary.getId());
        assertThat(persisted.getSummaryVersion()).isEqualTo(1);
    }

    /**
     * 创建一个包含用户问题和助手回答的完整测试轮次。
     *
     * @param userQuestion 用户问题
     * @param assistantAnswer 助手回答
     * @return 后续断言使用的持久化标识
     */
    private Fixture createCompletedTurn(String userQuestion, String assistantAnswer) {
        String userId = unique("user");
        String requestId = unique("request");
        ConversationEntity conversation = conversationMemoryService.createConversation(userId, "购票助手会话");
        ConversationMemoryService.StartedTurn started = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId, conversation.getId(), requestId, requestId, userQuestion, 10));
        MessageEntity assistant = conversationMemoryService.completeTurn(
                new ConversationMemoryService.CompleteTurnCommand(
                        userId, started.turnId(), assistantAnswer, 10));
        return new Fixture(
                userId, requestId, conversation.getId(), started.turnId(),
                started.userMessageId(), assistant.getId());
    }

    /**
     * 生成符合数据库长度约束的唯一测试值。
     *
     * @param prefix 可读前缀
     * @return 唯一字符串
     */
    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 完整轮次的测试标识集合。
     *
     * @param userId 用户标识
     * @param requestId 请求标识
     * @param conversationId 会话标识
     * @param turnId 轮次标识
     * @param userMessageId 用户消息标识
     * @param assistantMessageId 助手消息标识
     */
    private record Fixture(
            String userId,
            String requestId,
            String conversationId,
            String turnId,
            String userMessageId,
            String assistantMessageId) {
    }
}
