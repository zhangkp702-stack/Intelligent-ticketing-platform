package org.opengoofy.index12306.ai.mcpserver.tool;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.mcpserver.client.TicketBusinessClient;
import org.opengoofy.index12306.ai.mcpserver.security.McpCallerIdentity;
import org.opengoofy.index12306.ai.mcpserver.security.McpRequestAuthenticator;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.PassengerView;
import org.springaicommunity.mcp.annotation.McpMeta;
import org.springaicommunity.mcp.annotation.McpTool;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证只读工具注册范围和用户身份传递方式。
 */
class TicketQueryToolsTests {

    /**
     * 验证阶段五只注册五个只读工具，避免写操作提前进入模型工具集合。
     */
    @Test
    void registersOnlyExpectedReadOnlyTools() {
        // 从工具方法注解读取对外名称，防止后续误注册购票、退款或取消工具。
        Set<String> names = Stream.of(TicketQueryTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .map(McpTool::name)
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrder(
                "resolve_station",
                "query_tickets",
                "query_train_stops",
                "list_my_passengers",
                "list_my_orders");
    }

    /**
     * 验证当前用户乘车人查询只使用签名元数据身份，不接受用户标识工具参数。
     *
     * @throws Exception 反射读取方法失败时抛出
     */
    @Test
    void passengerToolUsesAuthenticatedMetadataIdentity() throws Exception {
        McpRequestAuthenticator authenticator = mock(McpRequestAuthenticator.class);
        TicketBusinessClient businessClient = mock(TicketBusinessClient.class);
        McpMeta meta = new McpMeta(java.util.Map.of());
        McpCallerIdentity identity = new McpCallerIdentity(
                "request-a", "user-a", "alice", "conversation-a", "turn-a", "topic-a");
        PassengerView passenger = new PassengerView("passenger-a", "张三", 0, "1***********1", 0, "138****0000", 1);
        when(authenticator.authenticate(meta)).thenReturn(identity);
        when(businessClient.listPassengers(identity)).thenReturn(List.of(passenger));
        TicketQueryTools tools = new TicketQueryTools(authenticator, businessClient);

        // 工具公开方法只有 McpMeta 参数，用户标识由鉴权器返回。
        Method method = TicketQueryTools.class.getMethod("listMyPassengers", McpMeta.class);
        assertThat(method.getParameterTypes()).containsExactly(McpMeta.class);
        assertThat(tools.listMyPassengers(meta)).containsExactly(passenger);
        verify(businessClient).listPassengers(identity);
    }
}
