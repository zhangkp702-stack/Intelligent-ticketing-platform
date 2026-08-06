package org.opengoofy.index12306.ai.agentservice.workflow.service.impl;

import org.opengoofy.index12306.ai.agentservice.workflow.dao.entity.AgentWorkflowEntity;
import org.opengoofy.index12306.ai.agentservice.workflow.dao.repository.AgentWorkflowRepository;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowStage;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowType;
import org.opengoofy.index12306.ai.agentservice.workflow.service.AgentWorkflowService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * 管理会话级业务工作流的创建、恢复和阶段推进，不在模型上下文中保存可执行状态。
 */
@Service
public class AgentWorkflowServiceImpl implements AgentWorkflowService {

    private static final Duration DEFAULT_WORKFLOW_TTL = Duration.ofMinutes(30);
    private static final Set<WorkflowStage> TERMINAL_STAGES = Set.of(
            WorkflowStage.COMPLETED,
            WorkflowStage.EXPIRED);

    private final AgentWorkflowRepository workflowRepository;
    private final Clock clock;

    /**
     * 创建工作流生命周期服务。
     *
     * @param workflowRepository 工作流仓储
     * @param clock 统一 UTC 时钟
     */
    public AgentWorkflowServiceImpl(AgentWorkflowRepository workflowRepository, Clock clock) {
        this.workflowRepository = workflowRepository;
        this.clock = clock;
    }

    /**
     * 启动指定类型的工作流；同一用户会话已有同类型活动工作流时直接恢复。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     * @param workflowType 工作流类型
     * @param initialStage 初始阶段
     * @param contextJson 已由服务端校验且不含敏感信息的上下文 JSON
     * @return 新建或恢复的活动工作流
     */
    @Transactional
    @Override
    public AgentWorkflowEntity startOrResume(
            String userId,
            String conversationId,
            WorkflowType workflowType,
            WorkflowStage initialStage,
            String contextJson) {
        requireText(userId, "用户标识不能为空");
        requireText(conversationId, "会话标识不能为空");
        WorkflowType normalizedWorkflowType = requireWorkflowType(workflowType);
        requireNonTerminalStage(initialStage, "初始阶段必须是可执行阶段");
        String normalizedContext = normalizeContext(contextJson);
        Instant now = clock.instant();
        String activeScopeKey = AgentWorkflowEntity.activeScopeKey(
                userId.trim(), conversationId.trim(), normalizedWorkflowType);

        // 活动作用域按工作流类型隔离，购票、退票等链路可以在同一会话中分别存在。
        Optional<AgentWorkflowEntity> active = workflowRepository
                .findLockedByActiveScopeKey(activeScopeKey);
        if (active.isPresent()) {
            AgentWorkflowEntity workflow = active.get();
            if (!TERMINAL_STAGES.contains(workflow.getStage()) && workflow.getExpiresAt().isAfter(now)) {
                // 作用域唯一记录仍有效时直接恢复，避免重复创建相同业务链路。
                return workflow;
            }
            // 历史过期记录会先释放作用域，随后才能安全创建新的同类型工作流。
            workflow.expire(now);
            workflowRepository.updateById(workflow);
        }

        // 新工作流统一设置有限有效期，避免长期未完成的会话状态污染后续请求。
        AgentWorkflowEntity workflow = AgentWorkflowEntity.create(
                userId.trim(),
                conversationId.trim(),
                normalizedWorkflowType,
                initialStage,
                normalizedContext,
                now.plus(DEFAULT_WORKFLOW_TTL),
                now);
        try {
            workflowRepository.insert(workflow);
            return workflow;
        } catch (DuplicateKeyException ignored) {
            // 极端并发下由唯一索引裁决后，读取已提交的同作用域记录并恢复，而不是向用户暴露重复创建错误。
            return workflowRepository.findFirstByUserIdAndConversationIdAndWorkflowType(
                            userId.trim(), conversationId.trim(), normalizedWorkflowType,
                            TERMINAL_STAGES, clock.instant())
                    .orElseThrow(() -> new IllegalStateException("活动工作流创建冲突，请重试"));
        }
    }

    /**
     * 查询会话中指定类型且可以继续执行的工作流。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     * @param workflowType 工作流类型
     * @return 未完成且未过期的工作流
     */
    @Transactional(readOnly = true)
    @Override
    public Optional<AgentWorkflowEntity> findActive(
            String userId,
            String conversationId,
            WorkflowType workflowType) {
        requireText(userId, "用户标识不能为空");
        requireText(conversationId, "会话标识不能为空");
        WorkflowType normalizedWorkflowType = requireWorkflowType(workflowType);

        // 查询同时限定类型、终态和过期时间，调用方不会误取其他业务链路。
        return workflowRepository
                .findFirstByUserIdAndConversationIdAndWorkflowType(
                        userId.trim(), conversationId.trim(), normalizedWorkflowType,
                        TERMINAL_STAGES, clock.instant());
    }

    /**
     * 按标识查询属于当前用户且仍可推进的工作流。
     *
     * @param userId 当前用户标识
     * @param workflowId 工作流标识
     * @return 未结束且未过期的工作流
     */
    @Transactional(readOnly = true)
    @Override
    public Optional<AgentWorkflowEntity> findActiveById(String userId, String workflowId) {
        requireText(userId, "用户标识不能为空");
        requireText(workflowId, "工作流标识不能为空");

        // 标识查询仍校验用户归属、终态和有效期，不能把工作流 ID 当作访问凭证。
        return Optional.ofNullable(workflowRepository.selectById(workflowId.trim()))
                .filter(workflow -> workflow.getUserId().equals(userId.trim()))
                .filter(workflow -> !TERMINAL_STAGES.contains(workflow.getStage()))
                .filter(workflow -> workflow.getExpiresAt().isAfter(clock.instant()));
    }

    /**
     * 在校验用户边界、当前阶段和推进方向后更新工作流上下文。
     *
     * @param userId 当前用户标识
     * @param workflowId 工作流标识
     * @param expectedStage 调用方期望的当前阶段
     * @param nextStage 下一阶段
     * @param contextJson 已由服务端校验且不含敏感信息的上下文 JSON
     * @return 推进后的工作流
     */
    @Transactional
    @Override
    public AgentWorkflowEntity advance(
            String userId,
            String workflowId,
            WorkflowStage expectedStage,
            WorkflowStage nextStage,
            String contextJson) {
        requireText(userId, "用户标识不能为空");
        requireText(workflowId, "工作流标识不能为空");
        requireNonTerminalStage(expectedStage, "期望阶段必须是可执行阶段");
        requireNonTerminalStage(nextStage, "下一阶段必须是可执行阶段");
        Instant now = clock.instant();

        // 写锁保证同一个阶段只能被一个请求成功推进。
        AgentWorkflowEntity workflow = loadOwnedWorkflow(userId.trim(), workflowId.trim());
        requireActive(workflow, now);
        requireExpectedStage(workflow, expectedStage);
        if (nextStage.ordinal() <= expectedStage.ordinal()) {
            throw new IllegalStateException("工作流阶段只能向前推进");
        }

        // 只保存服务端确认过的业务字段，不接受证件号等敏感信息作为工作流上下文。
        workflow.advance(nextStage, normalizeContext(contextJson), now);
        // MyBatis-Plus 不跟踪实体脏状态，阶段推进后立即以版本字段更新工作流。
        workflowRepository.updateById(workflow);
        return workflow;
    }

    /**
     * 将活动工作流标记为完成并保留最终的脱敏上下文。
     *
     * @param userId 当前用户标识
     * @param workflowId 工作流标识
     * @param expectedStage 调用方期望的当前阶段
     * @param contextJson 最终脱敏上下文 JSON
     * @return 已完成的工作流
     */
    @Transactional
    @Override
    public AgentWorkflowEntity complete(
            String userId,
            String workflowId,
            WorkflowStage expectedStage,
            String contextJson) {
        requireText(userId, "用户标识不能为空");
        requireText(workflowId, "工作流标识不能为空");
        requireNonTerminalStage(expectedStage, "期望阶段必须是可执行阶段");
        Instant now = clock.instant();

        // 完成操作同样锁定并校验当前阶段，避免旧请求覆盖更新后的链路状态。
        AgentWorkflowEntity workflow = loadOwnedWorkflow(userId.trim(), workflowId.trim());
        requireActive(workflow, now);
        requireExpectedStage(workflow, expectedStage);
        workflow.advance(WorkflowStage.COMPLETED, normalizeContext(contextJson), now);
        workflowRepository.updateById(workflow);
        return workflow;
    }

    /**
     * 在保持当前阶段不变的情况下替换服务端校验后的工作流上下文。
     *
     * @param userId 当前用户标识
     * @param workflowId 工作流标识
     * @param expectedStage 调用方期望的当前阶段
     * @param contextJson 新的脱敏上下文 JSON
     * @return 更新后的工作流
     */
    @Transactional
    @Override
    public AgentWorkflowEntity updateContext(
            String userId,
            String workflowId,
            WorkflowStage expectedStage,
            String contextJson) {
        requireText(userId, "用户标识不能为空");
        requireText(workflowId, "工作流标识不能为空");
        requireNonTerminalStage(expectedStage, "期望阶段必须是可执行阶段");
        Instant now = clock.instant();

        // 写锁和期望阶段共同防止较早请求覆盖用户刚刚提交的选择结果。
        AgentWorkflowEntity workflow = loadOwnedWorkflow(userId.trim(), workflowId.trim());
        requireActive(workflow, now);
        requireExpectedStage(workflow, expectedStage);
        workflow.advance(expectedStage, normalizeContext(contextJson), now);
        workflowRepository.updateById(workflow);
        return workflow;
    }

    /**
     * 主动终止仍在执行的工作流，使后续请求不能继续恢复或推进。
     *
     * @param userId 当前用户标识
     * @param workflowId 工作流标识
     * @return 已过期的工作流
     */
    @Transactional
    @Override
    public AgentWorkflowEntity expire(String userId, String workflowId) {
        requireText(userId, "用户标识不能为空");
        requireText(workflowId, "工作流标识不能为空");
        Instant now = clock.instant();

        // 主动终止只改变生命周期状态，不改写已经持久化的业务上下文。
        AgentWorkflowEntity workflow = loadOwnedWorkflow(userId.trim(), workflowId.trim());
        if (!TERMINAL_STAGES.contains(workflow.getStage())) {
            workflow.expire(now);
            workflowRepository.updateById(workflow);
        }
        return workflow;
    }

    /**
     * 加载并校验工作流归属，阻止跨用户读取或推进业务状态。
     *
     * @param userId 当前用户标识
     * @param workflowId 工作流标识
     * @return 已锁定且属于当前用户的工作流
     */
    private AgentWorkflowEntity loadOwnedWorkflow(String userId, String workflowId) {
        // 锁定查询为后续阶段校验和状态更新提供同一事务内的一致视图。
        AgentWorkflowEntity workflow = workflowRepository.findLockedById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("工作流不存在"));
        if (!workflow.getUserId().equals(userId)) {
            throw new SecurityException("无权访问该工作流");
        }
        return workflow;
    }

    /**
     * 校验工作流仍处于可推进状态且尚未超过有效期。
     *
     * @param workflow 当前工作流
     * @param now 当前时间
     */
    private void requireActive(AgentWorkflowEntity workflow, Instant now) {
        if (TERMINAL_STAGES.contains(workflow.getStage())) {
            throw new IllegalStateException("工作流已经结束");
        }
        if (!workflow.getExpiresAt().isAfter(now)) {
            // 超时判断直接拒绝推进；显式清理任务可随后把记录转换为 EXPIRED 终态。
            throw new IllegalStateException("工作流已经过期");
        }
    }

    /**
     * 校验调用方观察到的阶段与数据库当前阶段一致。
     *
     * @param workflow 当前工作流
     * @param expectedStage 调用方期望阶段
     */
    private void requireExpectedStage(AgentWorkflowEntity workflow, WorkflowStage expectedStage) {
        if (workflow.getStage() != expectedStage) {
            throw new IllegalStateException("工作流阶段已经变化，请刷新后重试");
        }
    }

    /**
     * 校验阶段不是完成或过期终态。
     *
     * @param stage 待校验阶段
     * @param message 校验失败提示
     */
    private void requireNonTerminalStage(WorkflowStage stage, String message) {
        if (stage == null || TERMINAL_STAGES.contains(stage)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 校验工作流类型存在。
     *
     * @param workflowType 待校验工作流类型
     * @return 已校验工作流类型
     */
    private WorkflowType requireWorkflowType(WorkflowType workflowType) {
        if (workflowType == null) {
            throw new IllegalArgumentException("工作流类型不能为空");
        }
        return workflowType;
    }

    /**
     * 规范化工作流上下文，空上下文统一保存为空 JSON 对象。
     *
     * @param contextJson 原始上下文 JSON
     * @return 可持久化的上下文文本
     */
    private String normalizeContext(String contextJson) {
        // 阶段一只维护生命周期，不解析具体业务字段；字段级校验由后续具体工作流负责。
        return StringUtils.hasText(contextJson) ? contextJson.trim() : "{}";
    }

    /**
     * 校验必填文本并拒绝纯空白输入。
     *
     * @param value 待校验文本
     * @param message 校验失败提示
     */
    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
