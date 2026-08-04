package org.opengoofy.index12306.ai.agentservice.chat.execution.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionStatus;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.AgentBaseEntity;

import java.time.Instant;
import java.util.Objects;

/**
 * 保存单个服务端任务的不可变计划、执行状态和可复用终态结果。
 */
@Getter
@TableName("t_agent_task_execution")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskExecutionEntity extends AgentBaseEntity {

    /**
     * 任务所属的服务端问答轮次标识。
     */
    private String turnId;

    /**
     * 任务在当前轮次计划中的执行顺序。
     */
    private int sequenceNo;

    /**
     * 任务对应的受控业务意图。
     */
    private AgentIntent intent;

    /**
     * 完成服务端任务标识重写后的不可变计划 JSON。
     */
    private String planJson;

    /**
     * 持久化任务当前执行状态。
     */
    private TaskExecutionStatus status;

    /**
     * 可供恢复流程复用的结构化安全结果 JSON。
     */
    private String resultJson;

    /**
     * 任务已经被领取执行的次数。
     */
    private int attemptCount;

    /**
     * 任务执行失败时记录的稳定分类。
     */
    private String failureCategory;

    /**
     * 最近一次任务执行开始时间。
     */
    private Instant startedAt;

    /**
     * 任务进入可复用终态的时间。
     */
    private Instant finishedAt;

    private TaskExecutionEntity(
            String turnId,
            int sequenceNo,
            AgentIntent intent,
            Instant now) {
        super(now);
        this.turnId = Objects.requireNonNull(turnId, "turnId");
        this.sequenceNo = sequenceNo;
        this.intent = Objects.requireNonNull(intent, "intent");
        this.status = TaskExecutionStatus.PENDING;
    }

    /**
     * 创建尚未执行的服务端任务检查点。
     *
     * @param turnId 所属轮次标识
     * @param sequenceNo 任务顺序
     * @param intent 受控业务意图
     * @param now 创建时间
     * @return 已生成服务端任务标识的待执行实体
     */
    public static TaskExecutionEntity pending(
            String turnId,
            int sequenceNo,
            AgentIntent intent,
            Instant now) {
        // 主键在实体创建时生成，模型返回的临时 taskId 不进入持久化业务边界。
        return new TaskExecutionEntity(turnId, sequenceNo, intent, now);
    }

    /**
     * 绑定已经完成服务端 taskId 重写的不可变计划 JSON。
     *
     * @param canonicalPlanJson 单任务规范计划 JSON
     * @param now 更新时间
     */
    public void bindPlan(String canonicalPlanJson, Instant now) {
        if (planJson != null) {
            throw new IllegalStateException("任务计划已经绑定");
        }
        // 计划只允许在插入前绑定一次，恢复执行始终读取相同输入。
        this.planJson = Objects.requireNonNull(canonicalPlanJson, "canonicalPlanJson");
        touch(now);
    }

    /**
     * 在当前 Turn 执行权下领取待执行任务或接管旧执行者遗留的运行中任务。
     *
     * @param now 当前时间
     */
    public void claim(Instant now) {
        if (isTerminal()) {
            throw new IllegalStateException("终态任务不能重新领取");
        }
        // Task 不再建立第二套租约；调用服务必须先持有当前 Turn fencing token。
        this.status = TaskExecutionStatus.RUNNING;
        this.attemptCount++;
        this.startedAt = now;
        this.failureCategory = null;
        touch(now);
    }

    /**
     * 在调用服务已经校验 Turn 执行权后提交可复用的任务终态结果。
     *
     * @param finalStatus 任务终态
     * @param safeResultJson 结构化安全结果 JSON
     * @param now 完成时间
     */
    public void complete(
            TaskExecutionStatus finalStatus,
            String safeResultJson,
            Instant now) {
        if (status != TaskExecutionStatus.RUNNING) {
            throw new IllegalStateException("任务不处于运行状态");
        }
        if (!isResultStatus(finalStatus)) {
            throw new IllegalArgumentException("任务结果不是允许的终态");
        }
        // 结果与终态在同一行原子提交，后续恢复直接复用而不再次调用业务链。
        this.status = finalStatus;
        this.resultJson = Objects.requireNonNull(safeResultJson, "safeResultJson");
        this.finishedAt = now;
        touch(now);
    }

    /**
     * 在相同 Turn fencing token 仍有效或刚被用户取消时收口运行中任务。
     *
     * @param safeResultJson 取消结果 JSON
     * @param now 取消时间
     * @return 当前执行权仍有效并成功取消时返回 true
     */
    public boolean cancel(
            String safeResultJson,
            Instant now) {
        if (status != TaskExecutionStatus.RUNNING) {
            return false;
        }
        // 是否属于当前 Turn 已由事务服务校验，实体只负责单向状态迁移。
        this.status = TaskExecutionStatus.CANCELLED;
        this.resultJson = Objects.requireNonNull(safeResultJson, "safeResultJson");
        this.finishedAt = now;
        touch(now);
        return true;
    }

    /**
     * 判断任务是否已经具有可复用的持久化终态。
     *
     * @return 不再允许重新执行时返回 true
     */
    public boolean isTerminal() {
        return status != TaskExecutionStatus.PENDING && status != TaskExecutionStatus.RUNNING;
    }

    /**
     * 校验任务终态是否可以携带结构化执行结果。
     *
     * @param candidate 待提交状态
     * @return 是否为合法任务结果终态
     */
    private boolean isResultStatus(TaskExecutionStatus candidate) {
        return candidate == TaskExecutionStatus.SUCCESS
                || candidate == TaskExecutionStatus.NEEDS_INPUT
                || candidate == TaskExecutionStatus.BLOCKED
                || candidate == TaskExecutionStatus.TIMED_OUT
                || candidate == TaskExecutionStatus.FAILED
                || candidate == TaskExecutionStatus.CANCELLED;
    }

}
