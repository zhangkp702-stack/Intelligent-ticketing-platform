package org.opengoofy.index12306.ai.agentservice.workflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.action.enums.PurchaseSeatClass;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.workflow.dao.repository.AgentWorkflowRepository;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerOption;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerSelectionRequest;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerSelectionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.PassengerResolutionStatus;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowStage;
import org.opengoofy.index12306.ai.agentservice.workflow.service.PurchaseWorkflowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证购票工作流中的乘车人精确匹配、表单选择和草案边界校验。
 */
@ActiveProfiles("test")
@SpringBootTest
class PurchaseWorkflowServiceTests {

    @Autowired
    private PurchaseWorkflowService purchaseWorkflowService;

    @Autowired
    private AgentWorkflowRepository workflowRepository;

    /**
     * 清理当前测试创建的工作流记录。
     */
    @AfterEach
    void cleanUp() {
        // 测试数据没有外键依赖，可按仓储边界直接清理。
        workflowRepository.deleteAll();
    }

    /**
     * 验证唯一姓名能够自动匹配，并且草案只能使用服务端确认的行程和乘车人。
     */
    @Test
    void resolvesUniqueNameAndValidatesDraftContext() {
        AgentRequestContext requestContext = requestContext();
        List<PassengerOption> options = List.of(
                new PassengerOption("passenger-1", "万重山", "31***********1234", 0, 1),
                new PassengerOption("passenger-2", "李明", "32***********5678", 0, 1));

        // 姓名唯一命中后，服务端直接保存乘车人标识并推进到草案创建阶段。
        PassengerResolutionResult result = purchaseWorkflowService.resolvePassengers(
                requestContext,
                "train-1",
                "北京南",
                "上海虹桥",
                "2026-07-22",
                List.of("万重山"),
                PurchaseSeatClass.SECOND_CLASS,
                options);

        assertThat(result.status()).isEqualTo(PassengerResolutionStatus.RESOLVED);
        assertThat(result.resolvedPassengers()).extracting("passengerId")
                .containsExactly("passenger-1");
        assertThat(workflowRepository.findById(result.workflowId()).orElseThrow().getStage())
                .isEqualTo(WorkflowStage.CREATING_DRAFT);

        // 完全一致的草案允许继续，替换乘车人或行程必须在服务端被拒绝。
        purchaseWorkflowService.validateDraft(
                requestContext.userId(), requestContext.conversationId(),
                "train-1", "北京南", "上海虹桥", "2026-07-22", List.of("passenger-1"));
        assertThatThrownBy(() -> purchaseWorkflowService.validateDraft(
                requestContext.userId(), requestContext.conversationId(),
                "train-1", "北京南", "上海虹桥", "2026-07-22", List.of("passenger-2")))
                .isInstanceOf(SecurityException.class);
    }

    /**
     * 验证未提供姓名时返回选择表单，并在用户勾选后持久化结果和推进阶段。
     */
    @Test
    void requestsSelectionAndPersistsSubmittedPassenger() {
        AgentRequestContext requestContext = requestContext();
        List<PassengerOption> options = List.of(
                new PassengerOption("passenger-1", "万重山", "31***********1234", 0, 1),
                new PassengerOption("passenger-2", "李明", "32***********5678", 0, 1));

        // 没有姓名时不猜测默认乘车人，而是返回当前账号的安全候选列表。
        PassengerResolutionResult result = purchaseWorkflowService.resolvePassengers(
                requestContext,
                "train-1",
                "北京南",
                "上海虹桥",
                "2026-07-22",
                List.of(),
                PurchaseSeatClass.SECOND_CLASS,
                options);

        assertThat(result.status()).isEqualTo(PassengerResolutionStatus.SELECTION_REQUIRED);
        assertThat(purchaseWorkflowService.findPendingSelection(
                requestContext.userId(), requestContext.conversationId())).isPresent();

        // 用户提交的标识必须来自候选列表，合法选择推进到草案创建阶段。
        PassengerSelectionResult selection = purchaseWorkflowService.selectPassengers(
                requestContext.userId(),
                result.workflowId(),
                new PassengerSelectionRequest(List.of("passenger-2")));
        assertThat(selection.stage()).isEqualTo(WorkflowStage.CREATING_DRAFT);
        assertThat(selection.selectedPassengers()).extracting("realName").containsExactly("李明");
        assertThat(purchaseWorkflowService.findPendingSelection(
                requestContext.userId(), requestContext.conversationId())).isEmpty();
    }

    /**
     * 创建相互隔离的测试请求上下文。
     *
     * @return 带唯一用户、会话和轮次标识的请求上下文
     */
    private AgentRequestContext requestContext() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        // 每个测试使用独立业务标识，避免共享 H2 数据影响恢复逻辑。
        return new AgentRequestContext(
                "request-" + suffix,
                "user-" + suffix,
                "tester",
                "conversation-" + suffix.substring(0, 16),
                "turn-" + suffix);
    }
}
