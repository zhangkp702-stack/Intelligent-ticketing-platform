package org.opengoofy.index12306.ai.agentservice.chat.execution;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionResult;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionStatus;
import org.opengoofy.index12306.ai.agentservice.chat.execution.exception.ExecutionLeaseLostException;
import org.opengoofy.index12306.ai.agentservice.chat.execution.service.TaskExecutionCheckpointService;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.PlannedTask;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TaskPlan;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TaskSlots;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.WorkflowRelation;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationMemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证多实例接管所依赖的服务端任务计划、检查点和两级 fencing token。
 */
@ActiveProfiles("test")
@SpringBootTest
@Import(DurableTaskExecutionPersistenceTests.ClockConfiguration.class)
class DurableTaskExecutionPersistenceTests {

    @Autowired
    private ConversationMemoryService conversationMemoryService;

    @Autowired
    private TaskExecutionCheckpointService checkpointService;

    @Autowired
    private MutableClock clock;

    /**
     * 验证过期 Turn 接管后复用原计划和已完成任务，并拒绝旧执行者的迟到提交。
     */
    @Test
    void expiredTurnReusesCheckpointsAndFencesOldExecutors() {
        String userId = unique("user");
        ConversationEntity conversation = conversationMemoryService.createConversation(userId, "任务恢复测试");
        ConversationMemoryService.PreparedTurn prepared = conversationMemoryService.prepareTurn(
                userId, conversation.getId());
        ConversationMemoryService.StartedTurn first = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId,
                        conversation.getId(),
                        prepared.turnId(),
                        prepared.submissionToken(),
                        "alice",
                        "查询车次后查看订单",
                        8));
        AgentRequestContext oldContext = context(userId, conversation.getId(), first);

        // 首次保存时把模型临时 ID 和依赖同时重写为不可变服务端任务 ID。
        TaskPlan durablePlan = checkpointService.persistPlan(oldContext, candidatePlan());
        PlannedTask firstTask = durablePlan.tasks().get(0);
        PlannedTask secondTask = durablePlan.tasks().get(1);
        assertThat(firstTask.taskId()).isNotEqualTo("task-1");
        assertThat(secondTask.taskId()).isNotEqualTo("task-2");
        assertThat(secondTask.dependsOn()).containsExactly(firstTask.taskId());

        // 已完成任务保存结构化结果；第二任务保留旧执行者遗留的运行中状态。
        TaskExecutionCheckpointService.TaskClaim firstClaim = checkpointService.claim(oldContext, firstTask);
        assertThat(firstClaim.execute()).isTrue();
        TaskExecutionResult firstResult = success(firstTask, "车次查询结果");
        checkpointService.complete(oldContext, firstTask, firstResult);
        TaskExecutionCheckpointService.TaskClaim oldSecondClaim = checkpointService.claim(oldContext, secondTask);
        assertThat(oldSecondClaim.execute()).isTrue();

        clock.advance(Duration.ofMinutes(3));
        assertThat(conversationMemoryService.findExpiredTurnCandidates())
                .extracting(ConversationMemoryService.ExpiredTurnCandidate::turnId)
                .contains(first.turnId());

        // 任一实例使用数据库固化的问题重新进入 startTurn，行锁内接管并提升 Turn fencing token。
        ConversationMemoryService.StartedTurn reclaimed = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId,
                        conversation.getId(),
                        prepared.turnId(),
                        "internal-recovery",
                        "alice",
                        "查询车次后查看订单",
                        8));
        AgentRequestContext newContext = context(userId, conversation.getId(), reclaimed);
        assertThat(reclaimed.created()).isTrue();
        assertThat(reclaimed.fencingToken()).isGreaterThan(first.fencingToken());

        // 恢复计划的 ID 和依赖保持不变，已完成任务直接复用而不再执行。
        TaskPlan recoveredPlan = checkpointService.findPlan(newContext).orElseThrow();
        assertThat(recoveredPlan).isEqualTo(durablePlan);
        TaskExecutionCheckpointService.TaskClaim reusedFirst = checkpointService.claim(
                newContext, recoveredPlan.tasks().get(0));
        assertThat(reusedFirst.execute()).isFalse();
        assertThat(reusedFirst.existingResult().content()).isEqualTo("车次查询结果");

        TaskExecutionCheckpointService.TaskClaim newSecondClaim = checkpointService.claim(
                newContext, recoveredPlan.tasks().get(1));
        assertThat(newSecondClaim.execute()).isTrue();

        // Task 不再维护第二套 token，旧执行者仍会被 Turn fencing token 拒绝。
        assertThatThrownBy(() -> checkpointService.complete(
                oldContext,
                secondTask,
                success(secondTask, "旧执行者结果")))
                .isInstanceOf(ExecutionLeaseLostException.class);
        assertThatThrownBy(() -> conversationMemoryService.completeTurn(
                new ConversationMemoryService.CompleteTurnCommand(
                        userId,
                        first.turnId(),
                        "旧执行者最终回答",
                        8,
                        first.executionOwner(),
                        first.fencingToken())))
                .isInstanceOf(IllegalStateException.class);
        // 旧响应流随后收到取消回调时，也不能把已经由新 Turn token 接管的任务标记为取消。
        checkpointService.cancel(oldContext, secondTask);

        TaskExecutionResult newResult = checkpointService.complete(
                newContext,
                recoveredPlan.tasks().get(1),
                success(recoveredPlan.tasks().get(1), "新执行者结果"));
        assertThat(newResult.content()).isEqualTo("新执行者结果");
    }

    /**
     * 创建包含一个显式依赖的模型候选计划。
     *
     * @return 使用临时任务标识的两任务计划
     */
    private TaskPlan candidatePlan() {
        TaskSlots slots = new TaskSlots(
                null, null, null, null, null, null, null, List.of(), null, null);
        PlannedTask first = new PlannedTask(
                "task-1", 0, AgentIntent.TRAIN_QUERY,
                "查询车次", "查询车次", slots,
                List.of(), List.of(), WorkflowRelation.INDEPENDENT, List.of());
        PlannedTask second = new PlannedTask(
                "task-2", 1, AgentIntent.ORDER_QUERY,
                "查看订单", "查看订单", slots,
                List.of(), List.of("task-1"), WorkflowRelation.INDEPENDENT, List.of());
        return new TaskPlan(List.of(first, second));
    }

    /**
     * 根据已领取轮次创建携带数据库执行权的内部上下文。
     *
     * @param userId 用户标识
     * @param conversationId 会话标识
     * @param turn 已领取轮次
     * @return 可用于任务检查点操作的上下文
     */
    private AgentRequestContext context(
            String userId,
            String conversationId,
            ConversationMemoryService.StartedTurn turn) {
        return new AgentRequestContext(
                turn.turnId(), userId, "alice", conversationId, turn.turnId(),
                turn.executionOwner(), turn.fencingToken());
    }

    /**
     * 创建可持久化的成功任务结果。
     *
     * @param task 当前任务
     * @param content 固定链结果正文
     * @return 成功结果
     */
    private TaskExecutionResult success(PlannedTask task, String content) {
        return new TaskExecutionResult(
                task.taskId(), task.sequence(), task.intent(), TaskExecutionStatus.SUCCESS,
                task.standaloneQuestion(), content, List.of(), null, null);
    }

    /**
     * 生成满足数据库字段长度约束的唯一测试值。
     *
     * @param prefix 可读前缀
     * @return 唯一标识
     */
    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 为租约过期测试提供无需真实等待的统一可变时钟。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        /**
         * 创建优先于生产时钟的测试时钟。
         *
         * @return 初始时间固定的可变 UTC 时钟
         */
        @Bean
        @Primary
        MutableClock durableTaskClock() {
            return new MutableClock(Instant.parse("2026-08-03T00:00:00Z"));
        }
    }

    /**
     * 只供当前测试推进租约时间的 UTC 时钟。
     */
    static final class MutableClock extends Clock {

        private Instant instant;

        /**
         * 创建指定初始时间的时钟。
         *
         * @param instant 初始时间
         */
        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        /**
         * 推进当前测试时间。
         *
         * @param duration 推进时长
         */
        private void advance(Duration duration) {
            // 所有依赖 Clock 的租约服务会在下一事务读取新时间。
            instant = instant.plus(duration);
        }

        /**
         * 返回 UTC 时区。
         *
         * @return UTC 时区
         */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /**
         * 当前测试固定使用 UTC，因此直接返回自身。
         *
         * @param zone 请求时区
         * @return 当前时钟
         */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /**
         * 返回当前可变时间。
         *
         * @return 当前时间
         */
        @Override
        public Instant instant() {
            return instant;
        }
    }
}
