package org.opengoofy.index12306.ai.agentservice.chat.stream;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatEvent;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.EventType;
import org.opengoofy.index12306.ai.agentservice.chat.stream.dao.repository.StreamEventRepository;
import org.opengoofy.index12306.ai.agentservice.chat.stream.service.DurableStreamEventService;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationMemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 SSE 事件序号、所有权、游标重放和数据库终态补发的真实持久化约束。
 */
@ActiveProfiles("test")
@SpringBootTest
class DurableStreamEventPersistenceTests {

    @Autowired
    private DurableStreamEventService durableStreamEventService;

    @Autowired
    private StreamEventRepository streamEventRepository;

    @Autowired
    private ConversationMemoryService conversationMemoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 验证同一 Turn 事件严格递增、按游标重放且终态后不再追加。
     */
    @Test
    void eventsAreSequencedReplayableAndClosedByTerminal() {
        RunningTurn fixture = createRunningTurn("查询北京到上海的车票");
        AgentRequestContext context = fixture.context();

        // 事件必须先持久化再返回给传输层，序号在数据库 Turn 行锁下连续分配。
        ChatEvent meta = durableStreamEventService.append(
                fixture.userId(), ChatEvent.meta(context, false)).orElseThrow();
        ChatEvent delta = durableStreamEventService.append(
                fixture.userId(), ChatEvent.delta(context, "北京到上海有票")).orElseThrow();
        conversationMemoryService.completeTurn(new ConversationMemoryService.CompleteTurnCommand(
                fixture.userId(), fixture.turnId(), "北京到上海有票", 8,
                fixture.executionOwner(), fixture.fencingToken()));
        ChatEvent done = durableStreamEventService.append(
                fixture.userId(), ChatEvent.done(context, "北京到上海有票", false)).orElseThrow();

        assertThat(List.of(meta.eventSequence(), delta.eventSequence(), done.eventSequence()))
                .containsExactly(1L, 2L, 3L);
        assertThat(durableStreamEventService.poll(fixture.userId(), fixture.turnId(), 1L, 20))
                .extracting(ChatEvent::type)
                .containsExactly(EventType.DELTA, EventType.DONE);
        assertThat(durableStreamEventService.append(
                fixture.userId(), ChatEvent.delta(context, "不应追加"))).isEmpty();

        // 越权用户不能凭 turnId 读取正文，损坏的过大游标则重发终态以结束连接。
        assertThatThrownBy(() -> durableStreamEventService.poll(
                unique("other"), fixture.turnId(), 0L, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(durableStreamEventService.poll(
                fixture.userId(), fixture.turnId(), 99L, 20))
                .singleElement()
                .satisfies(event -> assertThat(event.type()).isEqualTo(EventType.DONE));
    }

    /**
     * 验证回答已经提交但进程未写 DONE 时，可以从权威消息表补齐唯一终态。
     */
    @Test
    void completedTurnCreatesMissingTerminalEventOnReplay() {
        RunningTurn fixture = createRunningTurn("查询杭州到南京");
        conversationMemoryService.completeTurn(new ConversationMemoryService.CompleteTurnCommand(
                fixture.userId(), fixture.turnId(), "已找到可选车次", 8,
                fixture.executionOwner(), fixture.fencingToken()));

        // 模拟数据库回答提交后、SSE DONE 写入前进程退出，首次重放应生成序号 1 的恢复事件。
        List<ChatEvent> recovered = durableStreamEventService.poll(
                fixture.userId(), fixture.turnId(), 0L, 20);

        assertThat(recovered).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(EventType.DONE);
            assertThat(event.eventSequence()).isEqualTo(1L);
            assertThat(event.content()).isEqualTo("已找到可选车次");
            assertThat(event.reused()).isTrue();
        });
        assertThat(streamEventRepository.findTerminal(fixture.turnId())).isPresent();
    }

    /**
     * 验证并发连接共享数据库 Turn 锁后仍只会分配唯一且连续的事件序号。
     *
     * @throws Exception 并发任务执行失败时抛出
     */
    @Test
    void concurrentAppendsUseUniqueMonotonicTurnWatermark() throws Exception {
        RunningTurn fixture = createRunningTurn("并发查询车票");
        AgentRequestContext context = fixture.context();
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            // 每个任务通过独立事务和数据库连接竞争同一 Turn 行锁，模拟多实例同时追加。
            List<Callable<Long>> tasks = IntStream.range(0, 20)
                    .mapToObj(index -> (Callable<Long>) () -> durableStreamEventService.append(
                                    fixture.userId(), ChatEvent.delta(context, "分片-" + index))
                            .orElseThrow()
                            .eventSequence())
                    .toList();
            List<Future<Long>> futures = executor.invokeAll(tasks);
            List<Long> actualSequences = futures.stream()
                    .map(this::completedValue)
                    .sorted()
                    .toList();

            assertThat(actualSequences).containsExactlyElementsOf(
                    LongStream.rangeClosed(1L, 20L).boxed().toList());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 验证清理只删除过期终态事件，并在清理后沿用 Turn 水位补发最终结果。
     */
    @Test
    void retentionCleanupPreservesRunningEventsAndSequenceWatermark() {
        RunningTurn completed = createRunningTurn("清理后恢复回答");
        AgentRequestContext completedContext = completed.context();
        durableStreamEventService.append(
                completed.userId(), ChatEvent.meta(completedContext, false)).orElseThrow();
        durableStreamEventService.append(
                completed.userId(), ChatEvent.delta(completedContext, "最终回答")).orElseThrow();
        conversationMemoryService.completeTurn(new ConversationMemoryService.CompleteTurnCommand(
                completed.userId(), completed.turnId(), "最终回答", 6,
                completed.executionOwner(), completed.fencingToken()));
        durableStreamEventService.append(
                completed.userId(), ChatEvent.done(completedContext, "最终回答", false)).orElseThrow();

        RunningTurn running = createRunningTurn("仍在运行的回答");
        durableStreamEventService.append(
                running.userId(), ChatEvent.meta(running.context(), false)).orElseThrow();
        Instant oldCreatedAt = Instant.now().minus(Duration.ofDays(2));
        ageEvents(completed.turnId(), oldCreatedAt);
        ageEvents(running.turnId(), oldCreatedAt);

        // 相同过期时间下只清理 COMPLETED 轮次的三条事件，RUNNING 轮次仍可继续续传。
        int deleted = durableStreamEventService.cleanupTerminalEventsBefore(
                Instant.now().minus(Duration.ofDays(1)), 20);
        assertThat(deleted).isEqualTo(3);
        assertThat(streamEventRepository.findAfterSequence(running.turnId(), 0L, 20)).hasSize(1);
        assertThat(streamEventRepository.findAfterSequence(completed.turnId(), 0L, 20)).isEmpty();

        // 历史事件删除后从最终消息补发 DONE，序号沿用 Turn 水位从 3 增加到 4。
        assertThat(durableStreamEventService.poll(
                completed.userId(), completed.turnId(), 3L, 20))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.type()).isEqualTo(EventType.DONE);
                    assertThat(event.eventSequence()).isEqualTo(4L);
                    assertThat(event.content()).isEqualTo("最终回答");
                });
    }

    /**
     * 读取已经成功完成的并发任务返回值。
     *
     * @param future 并发追加任务
     * @return 任务分配到的事件序号
     */
    private Long completedValue(Future<Long> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new IllegalStateException("并发事件追加失败", exception);
        }
    }

    /**
     * 将指定 Turn 的事件创建时间调整到保留期之前。
     *
     * @param turnId 服务端轮次标识
     * @param createdAt 模拟的历史创建时间
     */
    private void ageEvents(String turnId, Instant createdAt) {
        // 测试只调整清理查询字段，不修改 Turn 状态、序号水位或事件正文。
        jdbcTemplate.update(
                "UPDATE t_agent_stream_event SET created_at = ? WHERE turn_id = ?",
                Timestamp.from(createdAt),
                turnId);
    }

    /**
     * 创建已领取执行租约但尚未完成的真实数据库轮次。
     *
     * @param question 当前用户问题
     * @return 流事件测试所需的轮次和执行权
     */
    private RunningTurn createRunningTurn(String question) {
        String userId = unique("stream-user");
        ConversationEntity conversation = conversationMemoryService.createConversation(
                userId, "SSE续传测试");
        ConversationMemoryService.PreparedTurn prepared = conversationMemoryService.prepareTurn(
                userId, conversation.getId());
        ConversationMemoryService.StartedTurn started = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId,
                        conversation.getId(),
                        prepared.turnId(),
                        prepared.submissionToken(),
                        "alice",
                        question,
                        8));
        return new RunningTurn(
                userId,
                conversation.getId(),
                started.turnId(),
                started.executionOwner(),
                started.fencingToken());
    }

    /**
     * 生成数据库长度约束内的唯一测试标识。
     *
     * @param prefix 可读前缀
     * @return 唯一字符串
     */
    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 流事件测试使用的运行中轮次执行权。
     *
     * @param userId 用户标识
     * @param conversationId 会话标识
     * @param turnId 服务端轮次标识
     * @param executionOwner 当前执行实例
     * @param fencingToken 当前围栏令牌
     */
    private record RunningTurn(
            String userId,
            String conversationId,
            String turnId,
            String executionOwner,
            long fencingToken) {

        /**
         * 构造与当前持久化执行权一致的请求上下文。
         *
         * @return 可用于创建 SSE 事件的上下文
         */
        private AgentRequestContext context() {
            // requestId 已统一为服务端 turnId，事件和业务审计共享同一稳定边界。
            return new AgentRequestContext(
                    turnId, userId, "alice", conversationId, turnId,
                    executionOwner, fencingToken);
        }
    }
}
