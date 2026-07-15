package org.opengoofy.index12306.ai.agentservice.memory;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MemorySummaryEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MemorySummaryStatus;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageRole;
import org.opengoofy.index12306.ai.agentservice.memory.domain.RouteDecision;
import org.opengoofy.index12306.ai.agentservice.memory.domain.SummaryTaskEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.SummaryTaskStatus;
import org.opengoofy.index12306.ai.agentservice.memory.domain.TopicEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.TurnEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.TurnStatus;
import org.opengoofy.index12306.ai.agentservice.memory.repository.ContextRouteLogRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.ContextSnapshotRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.MemorySummaryRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.MessageRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.SummaryTaskRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.TopicRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.TurnRepository;
import org.opengoofy.index12306.ai.agentservice.memory.service.ConversationMemoryService;
import org.opengoofy.index12306.ai.agentservice.memory.service.SummaryTaskService;
import org.opengoofy.index12306.ai.agentservice.memory.service.TopicContextService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证会话记忆、主题上下文和异步摘要任务在真实 JPA 映射下的核心约束。
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "index12306.agent.memory.summary-trigger-message-count=2")
class AgentMemoryPersistenceTests {

    @Autowired
    private ConversationMemoryService conversationMemoryService;

    @Autowired
    private TopicContextService topicContextService;

    @Autowired
    private SummaryTaskService summaryTaskService;

    @Autowired
    private TurnRepository turnRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private SummaryTaskRepository summaryTaskRepository;

    @Autowired
    private MemorySummaryRepository summaryRepository;

    @Autowired
    private ContextSnapshotRepository snapshotRepository;

    @Autowired
    private ContextRouteLogRepository routeLogRepository;

    /**
     * 验证同一请求不会重复创建轮次或消息，并且用户与助手消息序号严格递增。
     */
    @Test
    void conversationTurnIsIdempotentAndOrdered() {
        Fixture fixture = createCompletedTurn("查询明天北京到上海的票", "明天有多趟列车可选");

        // 使用相同请求标识再次启动轮次，应该复用首次写入的轮次和用户消息。
        ConversationMemoryService.StartedTurn retried = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        fixture.userId(), fixture.conversationId(), fixture.requestId(),
                        fixture.requestId(), "查询明天北京到上海的票", 10));
        MessageEntity completedAgain = conversationMemoryService.completeTurn(
                new ConversationMemoryService.CompleteTurnCommand(
                        fixture.userId(), fixture.turnId(), "不会重复写入的回答", 8));

        // 校验幂等返回、消息顺序和轮次最终状态均保持稳定。
        TurnEntity turn = turnRepository.findById(fixture.turnId()).orElseThrow();
        assertThat(retried.created()).isFalse();
        assertThat(retried.turnId()).isEqualTo(fixture.turnId());
        assertThat(retried.userMessageId()).isEqualTo(fixture.userMessageId());
        assertThat(retried.sequenceNo()).isEqualTo(1L);
        assertThat(completedAgain.getId()).isEqualTo(fixture.assistantMessageId());
        assertThat(completedAgain.getSequenceNo()).isEqualTo(2L);
        assertThat(turn.getStatus()).isEqualTo(TurnStatus.COMPLETED);
    }

    /**
     * 验证主题判定输入只包含历史用户问题，并验证上下文快照和路由日志可幂等回放。
     */
    @Test
    void routeInputExcludesHistoricalAssistantAnswersAndAuditsContext() {
        Fixture fixture = createCompletedTurn("查询北京到上海的余票", "历史助手回答不应进入主题判定");
        String currentRequestId = unique("route");

        // 写入本轮用户问题但暂不生成回答，用它构建主题判定输入。
        ConversationMemoryService.StartedTurn currentTurn = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        fixture.userId(), fixture.conversationId(), currentRequestId,
                        currentRequestId, "二等座还有吗", 6));
        TopicContextService.TopicRouteInput routeInput = topicContextService.buildRouteInput(
                fixture.userId(), fixture.conversationId(), currentTurn.userMessageId());

        // 主题选择只查看当前问题、历史用户问题和主题摘要卡片，不加载历史助手正文。
        assertThat(routeInput.currentQuestion()).isEqualTo("二等座还有吗");
        assertThat(routeInput.recentUserQuestions())
                .extracting(TopicContextService.UserQuestion::content)
                .containsExactly("查询北京到上海的余票")
                .doesNotContain("历史助手回答不应进入主题判定");
        assertThat(routeInput.topicCards())
                .extracting(TopicContextService.TopicSummaryCard::topicId)
                .contains(fixture.topicId());

        // 路由完成后加载完整主题上下文，并验证同一请求不会重复写审计数据。
        conversationMemoryService.assignTurnToTopic(
                fixture.userId(), currentTurn.turnId(), fixture.topicId());
        TopicContextService.TopicContext context = topicContextService.loadTopicContext(
                fixture.userId(), currentRequestId, fixture.conversationId(), fixture.topicId());
        topicContextService.loadTopicContext(
                fixture.userId(), currentRequestId, fixture.conversationId(), fixture.topicId());
        String firstRouteLogId = topicContextService.recordRouteDecision(new TopicContextService.RouteDecisionRecord(
                fixture.userId(), currentRequestId, fixture.conversationId(), currentTurn.userMessageId(),
                List.of(fixture.topicId()), fixture.topicId(), RouteDecision.SELECT_EXISTING,
                new BigDecimal("0.9500"), null));
        String retriedRouteLogId = topicContextService.recordRouteDecision(new TopicContextService.RouteDecisionRecord(
                fixture.userId(), currentRequestId, fixture.conversationId(), currentTurn.userMessageId(),
                List.of(fixture.topicId()), fixture.topicId(), RouteDecision.SELECT_EXISTING,
                new BigDecimal("0.9500"), null));

        // 完整上下文仍包含主题内用户与助手原始消息，快照和路由日志则各保留一份。
        assertThat(context.messages()).hasSize(3);
        assertThat(context.messages()).extracting(TopicContextService.ContextMessage::role)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER);
        assertThat(snapshotRepository.findByRequestId(currentRequestId)).isPresent();
        assertThat(routeLogRepository.findByRequestId(currentRequestId)).isPresent();
        assertThat(retriedRouteLogId).isEqualTo(firstRouteLogId);
    }

    /**
     * 验证达到阈值后只创建一个摘要任务，并能原子推进摘要版本和消息边界。
     */
    @Test
    void summaryTaskIsIdempotentAndCommitsVersion() {
        Fixture fixture = createCompletedTurn("查询后天去杭州的车票", "已找到可选车次");
        long throughSequence = messageRepository.findById(fixture.assistantMessageId())
                .orElseThrow()
                .getSequenceNo();

        // 同一主题和消息边界重复入队，应该返回同一个持久化任务。
        SummaryTaskEntity firstTask = summaryTaskService.enqueueIfNeeded(
                fixture.userId(), fixture.conversationId(), fixture.topicId(), throughSequence)
                .orElseThrow();
        SummaryTaskEntity retriedTask = summaryTaskService.enqueueIfNeeded(
                fixture.userId(), fixture.conversationId(), fixture.topicId(), throughSequence)
                .orElseThrow();
        assertThat(retriedTask.getId()).isEqualTo(firstTask.getId());

        // 领取任务后模拟摘要模型返回，并在一个事务中提交新摘要及主题边界。
        SummaryTaskService.SummaryWorkItem workItem = summaryTaskService.claim(firstTask.getId(), "test-worker");
        MemorySummaryEntity summary = summaryTaskService.complete(
                firstTask.getId(),
                new SummaryTaskService.SummaryGenerationResult(
                        "用户查询后天去杭州，系统已找到可选车次。",
                        "杭州购票查询",
                        "{\"intent\":\"ticket_query\"}",
                        "siliconflow", "summary-primary", "Qwen/Qwen3-8B"));

        // 校验来源消息、任务状态、摘要版本和主题压缩边界保持一致。
        SummaryTaskEntity completedTask = summaryTaskRepository.findById(firstTask.getId()).orElseThrow();
        TopicEntity topic = topicRepository.findById(fixture.topicId()).orElseThrow();
        assertThat(workItem.messages()).hasSize(2);
        assertThat(completedTask.getStatus()).isEqualTo(SummaryTaskStatus.SUCCEEDED);
        assertThat(summary.getVersionNo()).isEqualTo(1);
        assertThat(summary.getThroughSequence()).isEqualTo(throughSequence);
        assertThat(topic.getSummaryVersion()).isEqualTo(1);
        assertThat(topic.getSummarizedThroughSequence()).isEqualTo(throughSequence);
        assertThat(summaryRepository.findFirstByTopicIdAndStatusOrderByVersionNoDesc(
                fixture.topicId(), MemorySummaryStatus.ACTIVE))
                .get()
                .extracting(MemorySummaryEntity::getId)
                .isEqualTo(summary.getId());
    }

    /**
     * 创建一个包含用户问题、主题绑定和助手回答的完整测试轮次。
     *
     * @param userQuestion 用户问题
     * @param assistantAnswer 助手回答
     * @return 后续断言需要的持久化标识
     */
    private Fixture createCompletedTurn(String userQuestion, String assistantAnswer) {
        String userId = unique("user");
        String requestId = unique("request");

        // 创建独立会话和主题，避免不同测试之间共享业务数据。
        ConversationEntity conversation = conversationMemoryService.createConversation(userId, "购票助手会话");
        TopicEntity topic = conversationMemoryService.createTopic(
                userId, conversation.getId(), unique("topic"), "车票查询");
        ConversationMemoryService.StartedTurn startedTurn = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId, conversation.getId(), requestId, requestId, userQuestion, 10));

        // 绑定主题后写入助手回答，形成可用于上下文和摘要测试的完整消息对。
        conversationMemoryService.assignTurnToTopic(userId, startedTurn.turnId(), topic.getId());
        MessageEntity assistantMessage = conversationMemoryService.completeTurn(
                new ConversationMemoryService.CompleteTurnCommand(
                        userId, startedTurn.turnId(), assistantAnswer, 10));
        return new Fixture(
                userId, requestId, conversation.getId(), topic.getId(), startedTurn.turnId(),
                startedTurn.userMessageId(), assistantMessage.getId());
    }

    /**
     * 生成不超过数据库字段长度的唯一测试值。
     *
     * @param prefix 可读前缀
     * @return 唯一字符串
     */
    private String unique(String prefix) {
        // 去除 UUID 分隔符以兼容会话内主题键和请求标识的长度约束。
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 一个完整轮次的测试标识集合。
     *
     * @param userId 用户标识
     * @param requestId 请求标识
     * @param conversationId 会话标识
     * @param topicId 主题标识
     * @param turnId 轮次标识
     * @param userMessageId 用户消息标识
     * @param assistantMessageId 助手消息标识
     */
    private record Fixture(
            String userId,
            String requestId,
            String conversationId,
            String topicId,
            String turnId,
            String userMessageId,
            String assistantMessageId) {
    }
}
