package org.opengoofy.index12306.ai.agentservice.workflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.workflow.dao.repository.AgentWorkflowRepository;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.CancellableOrderOption;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.OrderResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.OrderSelectionRequest;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.OrderSelectionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.OrderResolutionStatus;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowStage;
import org.opengoofy.index12306.ai.agentservice.workflow.service.CancellationWorkflowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证取消订单工作流的自动定位、订单选择和草案边界校验。
 */
@ActiveProfiles("test")
@SpringBootTest
class CancellationWorkflowServiceTests {

    @Autowired
    private CancellationWorkflowService cancellationWorkflowService;

    @Autowired
    private AgentWorkflowRepository workflowRepository;

    /**
     * 清理当前测试创建的工作流记录。
     */
    @AfterEach
    void cleanUp() {
        // 工作流测试数据没有外键依赖，可以独立清理。
        workflowRepository.deleteAll();
    }

    /**
     * 验证订单号精确命中后自动推进，并拒绝模型替换取消目标。
     */
    @Test
    void resolvesExactOrderAndValidatesDraft() {
        AgentRequestContext requestContext = requestContext();
        List<CancellableOrderOption> orders = orders();

        // 精确订单号只命中一条本人可取消订单时直接进入草案阶段。
        OrderResolutionResult result = cancellationWorkflowService.resolveOrder(
                requestContext, "order-1", null, null, orders);

        assertThat(result.status()).isEqualTo(OrderResolutionStatus.RESOLVED);
        assertThat(result.selectedOrder().orderSn()).isEqualTo("order-1");
        assertThat(workflowRepository.findById(result.workflowId()).orElseThrow().getStage())
                .isEqualTo(WorkflowStage.CREATING_DRAFT);

        // 草案只能使用工作流选定的订单号。
        cancellationWorkflowService.validateDraft(
                requestContext.userId(), requestContext.conversationId(), "order-1");
        assertThatThrownBy(() -> cancellationWorkflowService.validateDraft(
                requestContext.userId(), requestContext.conversationId(), "order-2"))
                .isInstanceOf(SecurityException.class);
    }

    /**
     * 验证未指定订单时返回本人可取消订单表单，并持久化用户选择。
     */
    @Test
    void requestsOrderSelectionAndPersistsChoice() {
        AgentRequestContext requestContext = requestContext();

        // 未提供任何定位条件时不能猜测订单，必须等待结构化表单选择。
        OrderResolutionResult result = cancellationWorkflowService.resolveOrder(
                requestContext, null, null, null, orders());

        assertThat(result.status()).isEqualTo(OrderResolutionStatus.SELECTION_REQUIRED);
        assertThat(cancellationWorkflowService.findPendingSelection(
                requestContext.userId(), requestContext.conversationId()))
                .get().extracting(view -> view.orders().size()).isEqualTo(2);

        // 合法候选项推进到草案阶段，后续恢复不再返回旧选择表单。
        OrderSelectionResult selection = cancellationWorkflowService.selectOrder(
                requestContext.userId(), result.workflowId(), new OrderSelectionRequest("order-2"));
        assertThat(selection.stage()).isEqualTo(WorkflowStage.CREATING_DRAFT);
        assertThat(selection.selectedOrder().orderSn()).isEqualTo("order-2");
        assertThat(cancellationWorkflowService.findPendingSelection(
                requestContext.userId(), requestContext.conversationId())).isEmpty();
    }

    /**
     * 创建两条本人可取消订单测试数据。
     *
     * @return 可供自动定位和选择的订单列表
     */
    private List<CancellableOrderOption> orders() {
        // 两条订单使用不同车次和日期，覆盖无条件歧义与精确订单号匹配。
        return List.of(
                new CancellableOrderOption(
                        "order-1", "G9001", "北京南", "上海虹桥", "2026-07-22",
                        "08:00", "12:30", "万重山", 55300, 10, true),
                new CancellableOrderOption(
                        "order-2", "G9003", "北京南", "上海虹桥", "2026-07-23",
                        "09:00", "13:30", "李明", 60300, 10, true));
    }

    /**
     * 创建相互隔离的测试请求上下文。
     *
     * @return 带唯一业务标识的请求上下文
     */
    private AgentRequestContext requestContext() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        // 每个测试使用独立用户和会话，避免工作流恢复命中其他测试数据。
        return new AgentRequestContext(
                "request-" + suffix,
                "user-" + suffix,
                "tester",
                "conversation-" + suffix.substring(0, 16),
                "turn-" + suffix);
    }
}
