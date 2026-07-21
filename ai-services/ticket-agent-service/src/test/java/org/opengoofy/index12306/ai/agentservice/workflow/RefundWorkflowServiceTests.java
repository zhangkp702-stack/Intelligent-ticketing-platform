package org.opengoofy.index12306.ai.agentservice.workflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.workflow.dao.repository.AgentWorkflowRepository;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundOrderSelectionRequest;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundTicketSelectionRequest;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundableOrderOption;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundableTicketOption;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.RefundResolutionStatus;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowStage;
import org.opengoofy.index12306.ai.agentservice.workflow.service.RefundWorkflowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证退票工作流的订单定位、乘车人匹配、车票选择和草案边界校验。
 */
@ActiveProfiles("test")
@SpringBootTest
class RefundWorkflowServiceTests {

    @Autowired
    private RefundWorkflowService refundWorkflowService;

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
     * 验证订单和乘车人姓名唯一匹配后自动进入草案阶段，并拒绝替换退票范围。
     */
    @Test
    void resolvesPassengerNameAndValidatesDraftScope() {
        AgentRequestContext requestContext = requestContext();

        // 精确订单号先推进到车票解析阶段，再由服务端按姓名匹配唯一车票。
        RefundResolutionResult orderResult = refundWorkflowService.resolveOrder(
                requestContext, "order-1", null, null, List.of("万重山"), orders());
        RefundResolutionResult ticketResult = refundWorkflowService.resolveTickets(
                requestContext, orderResult.workflowId(), orders().get(0), tickets());

        assertThat(ticketResult.status()).isEqualTo(RefundResolutionStatus.RESOLVED);
        assertThat(ticketResult.refundType()).isZero();
        assertThat(ticketResult.selectedTickets())
                .extracting(RefundableTicketOption::orderItemId)
                .containsExactly("item-1");
        assertThat(workflowRepository.findById(ticketResult.workflowId()).orElseThrow().getStage())
                .isEqualTo(WorkflowStage.CREATING_DRAFT);

        // 草案必须保持服务端解析的订单、部分退票类型和子订单范围。
        refundWorkflowService.validateDraft(
                requestContext.userId(), requestContext.conversationId(),
                "order-1", 0, List.of("item-1"));
        assertThatThrownBy(() -> refundWorkflowService.validateDraft(
                requestContext.userId(), requestContext.conversationId(),
                "order-1", 0, List.of("item-2")))
                .isInstanceOf(SecurityException.class);
    }

    /**
     * 验证缺少订单和乘车人时依次返回订单单选及车票多选表单。
     */
    @Test
    void requestsOrderThenTicketSelection() {
        AgentRequestContext requestContext = requestContext();

        // 多个可退订单且没有定位条件时必须先等待订单选择。
        RefundResolutionResult orderResult = refundWorkflowService.resolveOrder(
                requestContext, null, null, null, List.of(), orders());
        assertThat(orderResult.status()).isEqualTo(RefundResolutionStatus.ORDER_SELECTION_REQUIRED);
        assertThat(refundWorkflowService.findPendingSelection(
                requestContext.userId(), requestContext.conversationId())).isPresent();

        // 选择订单后读取可退车票；没有姓名时必须展示车票多选表单。
        refundWorkflowService.selectOrder(
                requestContext.userId(), orderResult.workflowId(),
                new RefundOrderSelectionRequest("order-1"));
        RefundResolutionResult ticketResult = refundWorkflowService.resolveTickets(
                requestContext, orderResult.workflowId(), orders().get(0), tickets());
        assertThat(ticketResult.status()).isEqualTo(RefundResolutionStatus.TICKET_SELECTION_REQUIRED);

        // 勾选全部车票由服务端确定为全部退票，并推进到草案阶段。
        var selection = refundWorkflowService.selectTickets(
                requestContext.userId(), orderResult.workflowId(),
                new RefundTicketSelectionRequest(List.of("item-2", "item-1")));
        assertThat(selection.stage()).isEqualTo(WorkflowStage.CREATING_DRAFT);
        assertThat(selection.refundType()).isEqualTo(1);
        assertThat(selection.orderItemIds()).containsExactly("item-1", "item-2");
    }

    /** 创建两条可退订单测试数据。 */
    private List<RefundableOrderOption> orders() {
        return List.of(
                new RefundableOrderOption(
                        "order-1", "G9001", "北京南", "上海虹桥", "2026-07-22",
                        "08:00", "12:30", "万重山", 55300, 30, true),
                new RefundableOrderOption(
                        "order-2", "G9003", "北京南", "上海虹桥", "2026-07-23",
                        "09:00", "13:30", "李明", 60300, 30, true));
    }

    /** 创建同一订单中的两张可退车票。 */
    private List<RefundableTicketOption> tickets() {
        return List.of(
                new RefundableTicketOption("item-1", "万重山", 1, "03", "01A", 30, 27650),
                new RefundableTicketOption("item-2", "李明", 1, "03", "01B", 30, 27650));
    }

    /** 创建相互隔离的测试请求上下文。 */
    private AgentRequestContext requestContext() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return new AgentRequestContext(
                "request-" + suffix,
                "user-" + suffix,
                "tester",
                "conversation-" + suffix.substring(0, 16),
                "turn-" + suffix);
    }
}
