package org.opengoofy.index12306.ai.agentservice.action;

import org.opengoofy.index12306.ai.agentservice.action.service.PurchaseActionService;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.action.controller.AgentActionController;
import org.mockito.ArgumentCaptor;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.ActionStatusView;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.ConfirmPurchaseCommand;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.ConfirmPurchaseRequest;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证前端确认按钮通过独立控制器直接进入操作状态机。
 */
class AgentActionControllerTests {

    /**
     * 验证确认请求必须携带可复用的请求标识和幂等键。
     */
    @Test
    void confirmationRequiresStableRequestIdentifiers() {
        PurchaseActionService actionService = mock(PurchaseActionService.class);
        AgentActionController controller = new AgentActionController(actionService);

        // 缺少稳定标识时在进入状态机前拒绝，避免重试生成不同幂等语义。
        assertThatThrownBy(() -> controller.confirm(
                "user-1", "alice", null, null, "action-1",
                new ConfirmPurchaseRequest("confirmation-token")))
                .hasMessageContaining("请求标识和幂等键");
        verifyNoInteractions(actionService);
    }

    /**
     * 验证按钮请求直接传递给操作服务，不经过回答模型或聊天编排。
     */
    @Test
    void buttonConfirmationDelegatesDirectlyToActionStateMachine() {
        PurchaseActionService actionService = mock(PurchaseActionService.class);
        AgentActionController controller = new AgentActionController(actionService);
        ActionStatusView expected = new ActionStatusView(
                "action-1", "TICKET_PURCHASE", AgentActionStatus.EXECUTING,
                null, null, null);
        when(actionService.confirm(any())).thenReturn(expected);

        // 控制器只组装确认命令并调用确定性操作服务，模型不会参与按钮后的写操作。
        ActionStatusView actual = controller.confirm(
                "user-1", "alice", "request-1", "idempotency-1", "action-1",
                new ConfirmPurchaseRequest("confirmation-token"));

        ArgumentCaptor<ConfirmPurchaseCommand> commandCaptor =
                ArgumentCaptor.forClass(ConfirmPurchaseCommand.class);
        verify(actionService).confirm(commandCaptor.capture());
        ConfirmPurchaseCommand command = commandCaptor.getValue();
        assertThat(actual).isEqualTo(expected);
        assertThat(command.requestId()).isEqualTo("request-1");
        assertThat(command.idempotencyKey()).isEqualTo("idempotency-1");
        assertThat(command.userId()).isEqualTo("user-1");
        assertThat(command.actionId()).isEqualTo("action-1");
        assertThat(command.confirmationToken()).isEqualTo("confirmation-token");
    }
}
