package org.opengoofy.index12306.ai.agentservice.workflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.workflow.dao.entity.AgentWorkflowEntity;
import org.opengoofy.index12306.ai.agentservice.workflow.dao.repository.AgentWorkflowRepository;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowStage;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowType;
import org.opengoofy.index12306.ai.agentservice.workflow.service.AgentWorkflowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证会话级工作流的恢复、阶段推进、终态和用户隔离规则。
 */
@ActiveProfiles("test")
@SpringBootTest
class AgentWorkflowServiceTests {

    @Autowired
    private AgentWorkflowService workflowService;

    @Autowired
    private AgentWorkflowRepository workflowRepository;

    /**
     * 清理测试创建的工作流，避免共享 H2 上下文影响其他持久化测试。
     */
    @AfterEach
    void cleanUp() {
        // 工作流测试没有外键依赖，可以按仓储边界独立清理。
        workflowRepository.deleteAll();
    }

    /**
     * 验证同一会话恢复已有链路，并能够按预期阶段推进直至完成。
     */
    @Test
    void startsResumesAndCompletesWorkflow() {
        String userId = unique("user");
        String conversationId = unique("conversation");

        // 第一次调用创建购票工作流，重复调用必须复用同一条活动记录。
        AgentWorkflowEntity created = workflowService.startOrResume(
                userId,
                conversationId,
                WorkflowType.TICKET_PURCHASE,
                WorkflowStage.COLLECTING_TRIP,
                "{\"departure\":\"北京\"}");
        AgentWorkflowEntity resumed = workflowService.startOrResume(
                userId,
                conversationId,
                WorkflowType.TICKET_PURCHASE,
                WorkflowStage.COLLECTING_TRIP,
                "{}");

        assertThat(resumed.getId()).isEqualTo(created.getId());

        // 服务端校验期望阶段后向前推进，并在完成后不再返回活动工作流。
        AgentWorkflowEntity advanced = workflowService.advance(
                userId,
                created.getId(),
                WorkflowStage.COLLECTING_TRIP,
                WorkflowStage.SELECTING_TRAIN,
                "{\"departure\":\"北京\",\"arrival\":\"上海\"}");
        assertThat(advanced.getStage()).isEqualTo(WorkflowStage.SELECTING_TRAIN);

        AgentWorkflowEntity completed = workflowService.complete(
                userId,
                created.getId(),
                WorkflowStage.SELECTING_TRAIN,
                "{\"trainId\":\"G1\"}");
        assertThat(completed.getStage()).isEqualTo(WorkflowStage.COMPLETED);
        assertThat(workflowService.findActive(userId, conversationId)).isEmpty();
    }

    /**
     * 验证旧阶段请求和其他用户都不能推进当前工作流。
     */
    @Test
    void rejectsStaleStageAndCrossUserAdvance() {
        String userId = unique("user");
        AgentWorkflowEntity workflow = workflowService.startOrResume(
                userId,
                unique("conversation"),
                WorkflowType.TICKET_PURCHASE,
                WorkflowStage.COLLECTING_TRIP,
                "{}");

        // 阶段不一致说明调用方状态已经过时，必须刷新后再提交。
        assertThatThrownBy(() -> workflowService.advance(
                userId,
                workflow.getId(),
                WorkflowStage.SELECTING_TRAIN,
                WorkflowStage.SELECTING_PASSENGERS,
                "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("阶段已经变化");

        // 工作流标识不能绕过用户边界访问其他账号的业务状态。
        assertThatThrownBy(() -> workflowService.advance(
                unique("other-user"),
                workflow.getId(),
                WorkflowStage.COLLECTING_TRIP,
                WorkflowStage.SELECTING_TRAIN,
                "{}"))
                .isInstanceOf(SecurityException.class);
    }

    /**
     * 生成不超过数据库字段长度的测试标识。
     *
     * @param prefix 标识用途前缀
     * @return 当前测试唯一标识
     */
    private String unique(String prefix) {
        // UUID 去除分隔符后保持稳定长度，避免并行测试数据冲突。
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
