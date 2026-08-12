package org.opengoofy.index12306.ai.agentservice.conversation;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.conversation.context.ConversationHistoryContext;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationSummaryEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.MessageEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageRole;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.SummaryTaskEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.SummaryTaskStatus;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.TurnEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.TurnStatus;
import org.opengoofy.index12306.ai.agentservice.conversation.exception.TurnSubmissionException;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.ContextSnapshotRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.ConversationSummaryRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.SummaryTaskRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.TurnRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.context.ConversationContextLoader;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationMemoryService;
import org.opengoofy.index12306.ai.agentservice.conversation.service.SummaryTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证会话消息、唯一摘要和会话级上下文在真实 MyBatis-Plus 映射下的核心约束。
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "index12306.agent.memory.summary-trigger-message-count=2",
        "index12306.agent.memory.max-uncovered-turn-fallback=4"
})
class AgentMemoryPersistenceTests {

    @Autowired
    private ConversationMemoryService conversationMemoryService;

    @Autowired
    private ConversationContextLoader conversationContextLoader;

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
                        fixture.submissionToken(), "test-user", "查询明天北京到上海的票", 10));
        MessageEntity completedAgain = conversationMemoryService.completeTurn(
                new ConversationMemoryService.CompleteTurnCommand(
                        fixture.userId(), fixture.turnId(), "不会重复写入的回答", 8,
                        fixture.executionOwner(), fixture.fencingToken()));

        TurnEntity turn = Optional.ofNullable(turnRepository.selectById(fixture.turnId())).orElseThrow();
        assertThat(retried.created()).isFalse();
        assertThat(retried.turnId()).isEqualTo(fixture.turnId());
        assertThat(retried.sequenceNo()).isEqualTo(1L);
        assertThat(completedAgain.getId()).isEqualTo(fixture.assistantMessageId());
        assertThat(completedAgain.getSequenceNo()).isEqualTo(2L);
        assertThat(turn.getStatus()).isEqualTo(TurnStatus.COMPLETED);
    }

    /**
     * 验证服务端轮次在首次提交时固化内容指纹和租约，后续只能重放相同问题。
     */
    @Test
    void preparedTurnBindsPayloadAndExecutionLeaseOnce() {
        String userId = unique("turn-owner");
        ConversationEntity conversation = conversationMemoryService.createConversation(
                userId, "轮次协议测试");

        // 预创建只产生 DRAFT 轮次，不提前写入用户消息或领取执行权。
        ConversationMemoryService.PreparedTurn prepared = conversationMemoryService.prepareTurn(
                userId, conversation.getId());
        TurnEntity draft = Optional.ofNullable(turnRepository.selectById(prepared.turnId())).orElseThrow();
        assertThat(draft.getStatus()).isEqualTo(TurnStatus.DRAFT);
        assertThat(draft.getUserMessageId()).isNull();
        assertThat(draft.getPayloadHash()).isNull();
        assertThat(draft.getFencingToken()).isZero();

        // 首次合法提交在同一事务中绑定问题、用户消息、执行租约和 fencing token。
        ConversationMemoryService.StartedTurn started = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId, conversation.getId(), prepared.turnId(),
                        prepared.submissionToken(), "test-user", "查询上海到杭州的车票", 8));
        TurnEntity running = Optional.ofNullable(turnRepository.selectById(prepared.turnId())).orElseThrow();
        assertThat(started.created()).isTrue();
        assertThat(running.getStatus()).isEqualTo(TurnStatus.RUNNING);
        assertThat(running.getPayloadHash()).hasSize(64);
        assertThat(running.getLeaseOwner()).isNotBlank();
        assertThat(running.getLeaseUntil()).isAfter(running.getStartedAt());
        assertThat(running.getFencingToken()).isEqualTo(1L);

        // 相同内容重试复用原消息，不同内容不能借同一个 turnId 发起第二个业务请求。
        ConversationMemoryService.StartedTurn retried = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId, conversation.getId(), prepared.turnId(),
                        prepared.submissionToken(), "test-user", "查询上海到杭州的车票", 8));
        assertThat(retried.created()).isFalse();
        assertThat(retried.userMessageId()).isEqualTo(started.userMessageId());
        assertThatThrownBy(() -> conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId, conversation.getId(), prepared.turnId(),
                        prepared.submissionToken(), "test-user", "改成查询北京到广州", 8)))
                .isInstanceOf(TurnSubmissionException.class)
                .satisfies(exception -> assertThat(((TurnSubmissionException) exception).reason())
                        .isEqualTo(TurnSubmissionException.Reason.PAYLOAD_MISMATCH));
    }

    /**
     * 验证伪造提交令牌不能把服务端 DRAFT 轮次推进为运行状态。
     */
    @Test
    void preparedTurnRejectsInvalidSubmissionToken() {
        String userId = unique("token-owner");
        ConversationEntity conversation = conversationMemoryService.createConversation(
                userId, "提交令牌测试");
        ConversationMemoryService.PreparedTurn prepared = conversationMemoryService.prepareTurn(
                userId, conversation.getId());

        // 无效令牌在持久化用户消息之前被拒绝，轮次仍可使用原令牌正常提交。
        assertThatThrownBy(() -> conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId, conversation.getId(), prepared.turnId(),
                        "forged-submission-token", "test-user", "查询今天的车票", 6)))
                .isInstanceOf(TurnSubmissionException.class)
                .satisfies(exception -> assertThat(((TurnSubmissionException) exception).reason())
                        .isEqualTo(TurnSubmissionException.Reason.INVALID_TOKEN));
        TurnEntity draft = Optional.ofNullable(turnRepository.selectById(prepared.turnId())).orElseThrow();
        assertThat(draft.getStatus()).isEqualTo(TurnStatus.DRAFT);
        assertThat(draft.getUserMessageId()).isNull();
    }

    /**
     * 验证回答上下文只加载当前问题之前的完整轮次，不再执行主题判断。
     */
    @Test
    void conversationContextLoadsMessagesAndPersistsSnapshot() {
        Fixture fixture = createCompletedTurn("查询北京到上海的余票", "已有可选车次");

        // 新问题先由服务端预创建轮次，持久化后也不会混入最近完整历史轮次。
        ConversationMemoryService.PreparedTurn preparedTurn = conversationMemoryService.prepareTurn(
                fixture.userId(), fixture.conversationId());
        ConversationMemoryService.StartedTurn current = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        fixture.userId(), fixture.conversationId(), preparedTurn.turnId(),
                        preparedTurn.submissionToken(), "test-user", "二等座还有吗", 6));
        ConversationHistoryContext context = conversationContextLoader.load(
                fixture.userId(),
                current.turnId(),
                fixture.conversationId(),
                current.turnId(),
                current.userMessageId(),
                current.sequenceNo(),
                "二等座还有吗");

        assertThat(context.recentTurns()).hasSize(1);
        assertThat(context.recentTurns().get(0).userMessage().role()).isEqualTo(MessageRole.USER);
        assertThat(context.recentTurns().get(0).userMessage().content())
                .isEqualTo("查询北京到上海的余票");
        assertThat(context.recentTurns().get(0).assistantMessage().role())
                .isEqualTo(MessageRole.ASSISTANT);
        assertThat(context.recentTurns().get(0).assistantMessage().content())
                .isEqualTo("已有可选车次");
        assertThat(snapshotRepository.findByRequestId(current.turnId())).isPresent();
    }

    /**
     * 验证摘要尚未推进且未覆盖轮次超过窗口时，会话上下文完整加载全部未覆盖轮次。
     */
    @Test
    void conversationContextLoadsAllUncoveredTurnsWhenSummaryBehind() {
        Fixture fixture = createCompletedTurn("第一轮问题", "第一轮回答");
        // 在同一会话中追加三个完整轮次，使可查历史超过最近窗口但仍在回退上限内。
        appendCompletedTurn(fixture.userId(), fixture.conversationId(), "第二轮问题", "第二轮回答");
        appendCompletedTurn(fixture.userId(), fixture.conversationId(), "第三轮问题", "第三轮回答");
        appendCompletedTurn(fixture.userId(), fixture.conversationId(), "第四轮问题", "第四轮回答");

        // 当前运行中轮次仅作为独立问题，不能进入完整历史。
        ConversationMemoryService.PreparedTurn preparedTurn = conversationMemoryService.prepareTurn(
                fixture.userId(), fixture.conversationId());
        ConversationMemoryService.StartedTurn current = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        fixture.userId(), fixture.conversationId(), preparedTurn.turnId(),
                        preparedTurn.submissionToken(), "test-user", "当前问题", 4));
        ConversationHistoryContext context = conversationContextLoader.load(
                fixture.userId(), current.turnId(), fixture.conversationId(), current.turnId(),
                current.userMessageId(), current.sequenceNo(), "当前问题");

        assertThat(context.recentTurns())
                .extracting(turn -> turn.userMessage().content())
                .containsExactly("第一轮问题", "第二轮问题", "第三轮问题", "第四轮问题");
    }

    /**
     * 验证未覆盖轮次超过回退上限时不再加载更多原文，而是返回可重试的历史整理提示。
     */
    @Test
    void conversationContextRejectsWhenUncoveredTurnsExceedFallbackLimit() {
        Fixture fixture = createCompletedTurn("第一轮问题", "第一轮回答");
        appendCompletedTurn(fixture.userId(), fixture.conversationId(), "第二轮问题", "第二轮回答");
        appendCompletedTurn(fixture.userId(), fixture.conversationId(), "第三轮问题", "第三轮回答");
        appendCompletedTurn(fixture.userId(), fixture.conversationId(), "第四轮问题", "第四轮回答");
        appendCompletedTurn(fixture.userId(), fixture.conversationId(), "第五轮问题", "第五轮回答");

        ConversationMemoryService.PreparedTurn preparedTurn = conversationMemoryService.prepareTurn(
                fixture.userId(), fixture.conversationId());
        ConversationMemoryService.StartedTurn current = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        fixture.userId(), fixture.conversationId(), preparedTurn.turnId(),
                        preparedTurn.submissionToken(), "test-user", "当前问题", 4));

        assertThatThrownBy(() -> conversationContextLoader.load(
                fixture.userId(), current.turnId(), fixture.conversationId(), current.turnId(),
                current.userMessageId(), current.sequenceNo(), "当前问题"))
                .isInstanceOf(ConversationContextLoader.ConversationHistoryUnavailableException.class)
                .hasMessage("会话历史较多，系统正在整理，请稍后重试");
    }

    /**
     * 验证历史上下文优先使用已经持久化且校验成功的问题重写副本。
     */
    @Test
    void conversationContextUsesPersistedQuestionRewrite() {
        String userId = unique("rewrite-user");
        ConversationEntity conversation = conversationMemoryService.createConversation(userId, "问题重写测试");
        ConversationMemoryService.PreparedTurn prepared = conversationMemoryService.prepareTurn(
                userId, conversation.getId());
        ConversationMemoryService.StartedTurn started = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId, conversation.getId(), prepared.turnId(), prepared.submissionToken(),
                        "test-user", "二等座还有吗", 6));

        // 原始消息不改写，独立问题仅作为当前轮已校验的上下文副本保存。
        conversationMemoryService.recordQuestionResolution(
                userId,
                started.turnId(),
                started.executionOwner(),
                started.fencingToken(),
                true,
                "{\"tasks\":[{\"standaloneQuestion\":\"查询明天北京到上海的二等座余票\"}]}");
        conversationMemoryService.completeTurn(new ConversationMemoryService.CompleteTurnCommand(
                userId, started.turnId(), "明天仍有余票", 6,
                started.executionOwner(), started.fencingToken()));

        ConversationMemoryService.PreparedTurn currentPrepared = conversationMemoryService.prepareTurn(
                userId, conversation.getId());
        ConversationMemoryService.StartedTurn current = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId, conversation.getId(), currentPrepared.turnId(), currentPrepared.submissionToken(),
                        "test-user", "帮我看看一等座", 6));
        ConversationHistoryContext context = conversationContextLoader.load(
                userId, current.turnId(), conversation.getId(), current.turnId(),
                current.userMessageId(), current.sequenceNo(), "帮我看看一等座");

        assertThat(context.recentTurns()).singleElement()
                .extracting(turn -> turn.userMessage().content())
                .isEqualTo("查询明天北京到上海的二等座余票");
    }

    /**
     * 验证模型失败但已经持久化的用户问题不会从后续上下文中丢失。
     */
    @Test
    void conversationContextRetainsFailedTurnUserMessage() {
        Fixture fixture = createCompletedTurn("查询北京到上海的余票", "已有可选车次");
        ConversationMemoryService.PreparedTurn failedPrepared = conversationMemoryService.prepareTurn(
                fixture.userId(), fixture.conversationId());
        ConversationMemoryService.StartedTurn failed = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        fixture.userId(), fixture.conversationId(), failedPrepared.turnId(),
                        failedPrepared.submissionToken(), "test-user", "改成明天出发", 6));

        // 模型不可用后轮次没有助手消息，但用户补充的日期仍需进入后续请求的历史。
        conversationMemoryService.failTurn(
                fixture.userId(), failed.turnId(), failed.executionOwner(), failed.fencingToken(), "MODEL_UNAVAILABLE");
        ConversationMemoryService.PreparedTurn currentPrepared = conversationMemoryService.prepareTurn(
                fixture.userId(), fixture.conversationId());
        ConversationMemoryService.StartedTurn current = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        fixture.userId(), fixture.conversationId(), currentPrepared.turnId(),
                        currentPrepared.submissionToken(), "test-user", "二等座还有吗", 6));
        ConversationHistoryContext context = conversationContextLoader.load(
                fixture.userId(), current.turnId(), fixture.conversationId(), current.turnId(),
                current.userMessageId(), current.sequenceNo(), "二等座还有吗");

        assertThat(context.recentTurns())
                .extracting(turn -> turn.userMessage().content())
                .contains("改成明天出发");
        assertThat(context.recentTurns())
                .anySatisfy(turn -> {
                    assertThat(turn.userMessage().content()).isEqualTo("改成明天出发");
                    assertThat(turn.assistantMessage()).isNull();
                });
        TurnEntity failedTurn = Optional.ofNullable(turnRepository.selectById(failed.turnId())).orElseThrow();
        assertThat(failedTurn.getRewriteStatus()).isEqualTo("FAILED");
    }

    /**
     * 验证摘要阈值达到时，即使没有助手回答也会为用户消息创建异步摘要任务。
     */
    @Test
    void userOnlyMessagesTriggerSummaryTask() {
        String userId = unique("summary-user");
        ConversationEntity conversation = conversationMemoryService.createConversation(userId, "失败轮次摘要测试");
        ConversationMemoryService.PreparedTurn firstPrepared = conversationMemoryService.prepareTurn(
                userId, conversation.getId());
        ConversationMemoryService.StartedTurn first = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId, conversation.getId(), firstPrepared.turnId(), firstPrepared.submissionToken(),
                        "test-user", "第一条尚未回答的问题", 6));
        conversationMemoryService.failTurn(
                userId, first.turnId(), first.executionOwner(), first.fencingToken(), "MODEL_UNAVAILABLE");

        // 测试阈值为两条消息；第二条用户消息落库时不依赖助手回答即可创建任务。
        ConversationMemoryService.PreparedTurn secondPrepared = conversationMemoryService.prepareTurn(
                userId, conversation.getId());
        ConversationMemoryService.StartedTurn second = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId, conversation.getId(), secondPrepared.turnId(), secondPrepared.submissionToken(),
                        "test-user", "第二条尚未回答的问题", 6));

        SummaryTaskEntity task = summaryTaskRepository.findByConversationId(conversation.getId()).orElseThrow();
        assertThat(task.getDesiredThroughSequence()).isEqualTo(second.sequenceNo());
        assertThat(task.getStatus()).isEqualTo(SummaryTaskStatus.PENDING);
    }

    /**
     * 验证下一轮加载上下文时只能领取当前问题之前已经冻结的摘要批次。
     */
    @Test
    void contextClaimKeepsCurrentQuestionOutsideSummaryBatch() {
        Fixture fixture = createCompletedTurn("第一轮问题", "第一轮回答");
        ConversationMemoryService.PreparedTurn prepared = conversationMemoryService.prepareTurn(
                fixture.userId(), fixture.conversationId());
        ConversationMemoryService.StartedTurn current = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        fixture.userId(), fixture.conversationId(), prepared.turnId(), prepared.submissionToken(),
                        "test-user", "当前独立问题", 6));

        // 测试阈值为两条消息；上一轮任务边界保持为 2，不得被当前用户消息推进到 3。
        SummaryTaskService.SummaryWorkItem workItem = summaryTaskService.claimForContext(
                fixture.conversationId(), current.sequenceNo(), "context-test-worker").orElseThrow();
        assertThat(workItem.throughSequence()).isEqualTo(2L);
        assertThat(workItem.messages()).extracting(SummaryTaskService.SummarySourceMessage::content)
                .containsExactly("第一轮问题", "第一轮回答");
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

        SummaryTaskEntity completed = Optional.ofNullable(summaryTaskRepository.selectById(task.getId())).orElseThrow();
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
        ConversationEntity conversation = conversationMemoryService.createConversation(userId, "购票助手会话");
        // 轮次标识和提交令牌均由服务端生成，客户端重试只能复用这组凭证。
        ConversationMemoryService.PreparedTurn preparedTurn = conversationMemoryService.prepareTurn(
                userId, conversation.getId());
        ConversationMemoryService.StartedTurn started = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId, conversation.getId(), preparedTurn.turnId(),
                        preparedTurn.submissionToken(), "test-user", userQuestion, 10));
        MessageEntity assistant = conversationMemoryService.completeTurn(
                new ConversationMemoryService.CompleteTurnCommand(
                        userId, started.turnId(), assistantAnswer, 10,
                        started.executionOwner(), started.fencingToken()));
        return new Fixture(
                userId, started.turnId(), preparedTurn.submissionToken(), conversation.getId(), started.turnId(),
                started.userMessageId(), assistant.getId(), started.executionOwner(), started.fencingToken());
    }

    /**
     * 在指定会话中追加一个已完成的问答轮次。
     *
     * @param userId 会话所属用户
     * @param conversationId 目标会话标识
     * @param userQuestion 用户问题
     * @param assistantAnswer 助手回答
     */
    private void appendCompletedTurn(
            String userId,
            String conversationId,
            String userQuestion,
            String assistantAnswer) {
        // 每个追加轮次都使用服务端生成的轮次凭证，保持与真实提交流程一致。
        ConversationMemoryService.PreparedTurn preparedTurn = conversationMemoryService.prepareTurn(
                userId, conversationId);
        ConversationMemoryService.StartedTurn started = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId, conversationId, preparedTurn.turnId(),
                        preparedTurn.submissionToken(), "test-user", userQuestion, 10));
        conversationMemoryService.completeTurn(
                new ConversationMemoryService.CompleteTurnCommand(
                        userId, started.turnId(), assistantAnswer, 10,
                        started.executionOwner(), started.fencingToken()));
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
     * @param submissionToken 服务端签发的轮次提交令牌
     * @param conversationId 会话标识
     * @param turnId 轮次标识
     * @param userMessageId 用户消息标识
     * @param assistantMessageId 助手消息标识
     * @param executionOwner 当前轮次执行者
     * @param fencingToken 当前轮次围栏令牌
     */
    private record Fixture(
            String userId,
            String requestId,
            String submissionToken,
            String conversationId,
            String turnId,
            String userMessageId,
            String assistantMessageId,
            String executionOwner,
            long fencingToken) {
    }
}
