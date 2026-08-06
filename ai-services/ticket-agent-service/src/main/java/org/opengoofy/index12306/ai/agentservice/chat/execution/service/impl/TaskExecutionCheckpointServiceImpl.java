package org.opengoofy.index12306.ai.agentservice.chat.execution.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.ai.agentservice.chat.config.AgentTurnProperties;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionResult;
import org.opengoofy.index12306.ai.agentservice.chat.execution.dao.entity.TaskExecutionEntity;
import org.opengoofy.index12306.ai.agentservice.chat.execution.dao.repository.TaskExecutionRepository;
import org.opengoofy.index12306.ai.agentservice.chat.execution.exception.ExecutionLeaseLostException;
import org.opengoofy.index12306.ai.agentservice.chat.execution.service.TaskExecutionCheckpointService;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.PlannedTask;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TaskPlan;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.TurnEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.TurnStatus;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.ConversationRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.TurnRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 使用数据库唯一约束和 Turn fencing token 管理可恢复任务检查点。
 */
@Service
public class TaskExecutionCheckpointServiceImpl implements TaskExecutionCheckpointService {

    private final TaskExecutionRepository taskRepository;
    private final TurnRepository turnRepository;
    private final ConversationRepository conversationRepository;
    private final AgentTurnProperties turnProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 创建任务检查点服务。
     *
     * @param taskRepository 任务检查点仓储
     * @param turnRepository 轮次仓储
     * @param conversationRepository 会话仓储
     * @param turnProperties 执行租约配置
     * @param objectMapper 计划和结果序列化器
     * @param clock 统一时钟
     */
    public TaskExecutionCheckpointServiceImpl(
            TaskExecutionRepository taskRepository,
            TurnRepository turnRepository,
            ConversationRepository conversationRepository,
            AgentTurnProperties turnProperties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.taskRepository = taskRepository;
        this.turnRepository = turnRepository;
        this.conversationRepository = conversationRepository;
        this.turnProperties = turnProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 从任务检查点恢复已经使用服务端 taskId 固化的计划。
     *
     * @param context 当前执行上下文
     * @return 已持久化计划；没有任务行时为空
     */
    @Transactional(readOnly = true)
    @Override
    public Optional<TaskPlan> findPlan(AgentRequestContext context) {
        List<TaskExecutionEntity> tasks = taskRepository.findByTurnIdOrderBySequenceNoAsc(context.turnId());
        if (tasks.isEmpty()) {
            return Optional.empty();
        }
        // 每行保存单任务规范计划，恢复时按 sequence_no 重新组成不可变计划。
        return Optional.of(decodePlan(tasks));
    }

    /**
     * 原子重写模型临时标识并保存当前轮次唯一任务计划。
     *
     * @param context 当前执行上下文
     * @param candidatePlan 已通过确定性校验的模型计划
     * @return 使用服务端 taskId 的持久化计划
     */
    @Transactional
    @Override
    public TaskPlan persistPlan(AgentRequestContext context, TaskPlan candidatePlan) {
        // 防御性校验：计划或任务列表为空时无法固化，后续调度将拿不到任何可执行任务
        if (candidatePlan == null || candidatePlan.tasks() == null || candidatePlan.tasks().isEmpty()) {
            throw new IllegalArgumentException("任务计划不能为空");
        }
        // 所有写路径维持“会话 -> 轮次 -> 任务”锁顺序，并校验当前 Turn fencing token。
        TurnEntity turn = lockOwnedTurn(context);
        // 幂等检查：查询当前轮次是否已有固化任务行，防止并发重试时重复固化计划
        List<TaskExecutionEntity> existing = taskRepository.findByTurnIdOrderBySequenceNoAsc(context.turnId());
        // 已有任务行说明该轮次计划之前已固化，按 sequence 顺序解码复用，不再重复写入
        if (!existing.isEmpty()) {
            return decodePlan(existing);
        }

        // 统一取当前时钟时间，作为本次创建全部任务行的创建时间戳
        Instant now = clock.instant();
        // 收集待落库的任务实体，供最后批量插入使用
        List<TaskExecutionEntity> entities = new ArrayList<>();
        // 记录“模型临时 taskId -> 服务端主键”的映射，供依赖引用重写使用
        Map<String, String> serverTaskIds = new HashMap<>();
        // 遍历模型计划中的每个任务，先为它们生成服务端稳定标识
        for (PlannedTask task : candidatePlan.tasks()) {
            // 创建状态为 PENDING 的任务检查点实体，服务端主键在此刻生成
            TaskExecutionEntity entity = TaskExecutionEntity.pending(
                    context.turnId(), task.sequence(), task.intent(), now);
            // 收集实体用于后续统一插入
            entities.add(entity);
            // 建立模型 taskId 与服务端主键的对应关系
            serverTaskIds.put(task.taskId(), entity.getId());
        }

        // 依赖引用与任务主键一起重写，模型生成的 task-1 等文本不进入业务幂等边界。
        List<PlannedTask> durableTasks = new ArrayList<>();
        // 遍历每个任务，组装重写后的持久化版本
        for (int index = 0; index < candidatePlan.tasks().size(); index++) {
            // 取出当前待重写的模型任务
            PlannedTask source = candidatePlan.tasks().get(index);
            // 把 dependsOn 中的模型 taskId 通过映射表翻译为服务端主键
            List<String> dependencies = source.dependsOn().stream()
                    .map(serverTaskIds::get)
                    .toList();
            // 翻译结果存在 null 说明依赖指向了本计划之外的任务，重写阶段直接拦截，不让坏数据落库
            if (dependencies.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("任务依赖缺少服务端标识");
            }
            // 组装重写后的不可变计划：taskId 换成服务端主键，业务内容原样保留
            PlannedTask durable = new PlannedTask(
                    entities.get(index).getId(),
                    source.sequence(),
                    source.intent(),
                    source.originalClause(),
                    source.standaloneQuestion(),
                    source.slots(),
                    source.missingFields(),
                    dependencies,
                    source.workflowRelation(),
                    source.unresolvedReferences());
            // 收集中间产物，最后统一返回
            durableTasks.add(durable);
            // 把重写后的单任务计划 JSON 绑定到实体，保证恢复时始终读取同一份输入
            entities.get(index).bindPlan(writeJson(durable), now);
        }
        // 一次性批量插入全部任务检查点，本轮计划固化完成
        entities.forEach(taskRepository::insert);
        // 计划固化成功后同步顺延轮次租约，保证后续领取任务时执行权仍然有效
        heartbeatOwnedTurn(turn, context, now);
        // 返回使用服务端主键的持久化计划，供调度执行使用
        return new TaskPlan(List.copyOf(durableTasks));
    }

    /**
     * 在 Turn 执行权有效时领取任务，终态任务直接返回安全检查点。
     *
     * @param context 当前执行上下文
     * @param task 当前服务端任务
     * @return 是否需要执行以及可能存在的终态结果
     */
    @Transactional
    @Override
    public TaskClaim claim(AgentRequestContext context, PlannedTask task) {
        TurnEntity turn = lockOwnedTurn(context);
        TaskExecutionEntity entity = taskRepository.findLockedById(task.taskId())
                .orElseThrow(() -> new IllegalArgumentException("任务检查点不存在"));
        assertTaskBoundary(entity, context, task);
        if (entity.isTerminal()) {
            return new TaskClaim(false, readResult(entity));
        }

        Instant now = clock.instant();
        entity.claim(now);
        // Task 状态与 Turn 心跳在同一事务提交，旧执行者由 Turn fencing token 统一隔离。
        taskRepository.updateById(entity);
        heartbeatOwnedTurn(turn, context, now);
        return new TaskClaim(true, null);
    }

    /**
     * 提交任务终态，并使用 Turn fencing token 隔离旧执行者。
     *
     * @param context 当前执行上下文
     * @param task 当前服务端任务
     * @param result 结构化任务结果
     * @return 已持久化结果
     */
    @Transactional
    @Override
    public TaskExecutionResult complete(
            AgentRequestContext context,
            PlannedTask task,
            TaskExecutionResult result) {
        TurnEntity turn = lockOwnedTurn(context);
        TaskExecutionEntity entity = taskRepository.findLockedById(task.taskId())
                .orElseThrow(() -> new IllegalArgumentException("任务检查点不存在"));
        assertTaskBoundary(entity, context, task);
        Instant now = clock.instant();

        // 确认令牌和多态工作流视图不进入任务 JSON，恢复时从各自权威状态表重新签发或加载。
        TaskExecutionResult persistedResult = withoutEphemeralViews(result);
        entity.complete(
                persistedResult.status(),
                writeJson(persistedResult),
                now);
        taskRepository.updateById(entity);
        heartbeatOwnedTurn(turn, context, now);
        return persistedResult;
    }

    /**
     * 取消当前实例仍持有的运行中任务，旧 Turn token 的迟到回调保持无副作用。
     *
     * @param context 当前执行上下文
     * @param task 当前服务端任务
     */
    @Transactional
    @Override
    public void cancel(
            AgentRequestContext context,
            PlannedTask task) {
        // Turn 可能刚被用户取消，但必须保持相同 token；已由新 Turn token 接管时拒绝旧取消回调。
        TurnEntity observedTurn = Optional.ofNullable(turnRepository.selectById(context.turnId())).orElse(null);
        if (observedTurn == null) {
            return;
        }
        ConversationEntity conversation = conversationRepository.findLockedById(
                observedTurn.getConversationId()).orElse(null);
        TurnEntity turn = turnRepository.findLockedById(context.turnId()).orElse(null);
        if (conversation == null
                || turn == null
                || !conversation.belongsTo(context.userId())
                || turn.getFencingToken() != context.fencingToken()
                || (turn.getStatus() == TurnStatus.RUNNING
                    && !turn.isOwnedBy(context.executionOwner(), context.fencingToken()))
                || (turn.getStatus() != TurnStatus.RUNNING
                    && turn.getStatus() != TurnStatus.CANCELLED)) {
            return;
        }
        TaskExecutionEntity entity = taskRepository.findLockedById(task.taskId()).orElse(null);
        if (entity == null) {
            return;
        }
        TaskExecutionResult cancelled = TaskExecutionResult.cancelled(
                task.taskId(), task.sequence(), task.intent(), task.standaloneQuestion());
        if (entity.cancel(
                writeJson(cancelled),
                clock.instant())) {
            taskRepository.updateById(entity);
        }
    }

    /**
     * 按固定锁顺序读取并校验当前 Turn 执行权。
     *
     * @param context 当前执行上下文
     * @return 已锁定且归当前 token 所有的轮次
     */
    private TurnEntity lockOwnedTurn(AgentRequestContext context) {
        // 先快照读，根据turnid获取轮次
        TurnEntity observed = Optional.ofNullable(turnRepository.selectById(context.turnId()))
                .orElseThrow(() -> new ExecutionLeaseLostException("轮次不存在"));
        // 根据轮次获取会话
        ConversationEntity conversation = conversationRepository.findLockedById(observed.getConversationId())
                .orElseThrow(() -> new ExecutionLeaseLostException("会话不存在"));
        if (!conversation.getId().equals(context.conversationId())
                || !conversation.belongsTo(context.userId())) {
            throw new ExecutionLeaseLostException("轮次执行上下文不属于当前会话");
        }
        TurnEntity turn = turnRepository.findLockedById(context.turnId())
                .orElseThrow(() -> new ExecutionLeaseLostException("轮次不存在"));
        if (!turn.isOwnedBy(context.executionOwner(), context.fencingToken())) {
            throw new ExecutionLeaseLostException("轮次执行权已经失效");
        }
        return turn;
    }

    /**
     * 在任务状态变化事务内同步延长 Turn 租约。
     *
     * @param turn 已锁定轮次
     * @param context 当前执行上下文
     * @param now 当前时间
     */
    private void heartbeatOwnedTurn(
            TurnEntity turn,
            AgentRequestContext context,
            Instant now) {
        if (!turn.heartbeat(
                context.executionOwner(),
                context.fencingToken(),
                now.plus(turnProperties.executionLease()),
                now)) {
            throw new ExecutionLeaseLostException("轮次续租失败");
        }
        turnRepository.updateById(turn);
    }

    /**
     * 校验任务属于当前轮次且与固化的顺序和意图一致。
     *
     * @param entity 任务检查点
     * @param context 当前执行上下文
     * @param task 当前计划任务
     */
    private void assertTaskBoundary(
            TaskExecutionEntity entity,
            AgentRequestContext context,
            PlannedTask task) {
        if (!entity.getTurnId().equals(context.turnId())
                || entity.getSequenceNo() != task.sequence()
                || entity.getIntent() != task.intent()) {
            throw new IllegalArgumentException("任务不属于当前轮次计划");
        }
    }

    /**
     * 按任务顺序解码已经固化的服务端计划。
     *
     * @param entities 任务检查点列表
     * @return 服务端任务计划
     */
    private TaskPlan decodePlan(List<TaskExecutionEntity> entities) {
        List<PlannedTask> tasks = entities.stream()
                .map(entity -> readJson(entity.getPlanJson(), PlannedTask.class))
                .toList();
        return new TaskPlan(List.copyOf(tasks));
    }

    /**
     * 读取任务已经持久化的终态结果。
     *
     * @param entity 终态任务检查点
     * @return 不携带临时令牌的结构化结果
     */
    private TaskExecutionResult readResult(TaskExecutionEntity entity) {
        if (entity.getResultJson() == null) {
            throw new IllegalStateException("终态任务缺少结果检查点");
        }
        return readJson(entity.getResultJson(), TaskExecutionResult.class);
    }

    /**
     * 去除必须从权威状态表恢复的临时视图。
     *
     * @param result 原始任务结果
     * @return 可安全持久化的任务结果
     */
    private TaskExecutionResult withoutEphemeralViews(TaskExecutionResult result) {
        return new TaskExecutionResult(
                result.taskId(),
                result.sequence(),
                result.intent(),
                result.status(),
                result.question(),
                result.content(),
                result.missingFields(),
                null,
                null);
    }

    /**
     * 将计划或结果编码为数据库 JSON。
     *
     * @param value 待编码值
     * @return JSON 文本
     */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化任务检查点", exception);
        }
    }

    /**
     * 将数据库 JSON 解码为指定不可变类型。
     *
     * @param json JSON 文本
     * @param type 目标类型
     * @param <T> 目标类型参数
     * @return 解码结果
     */
    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取任务检查点", exception);
        }
    }
}
