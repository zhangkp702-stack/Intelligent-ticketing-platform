package org.opengoofy.index12306.ai.mcpserver.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.mcpserver.client.TicketBusinessClient;
import org.opengoofy.index12306.ai.mcpserver.security.McpCallerIdentity;
import org.opengoofy.index12306.ai.mcpserver.security.McpRequestAuthenticator;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.PassengerView;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.OrderDetailView;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.ConfirmedPurchasePassenger;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.ConfirmedPurchaseResult;
import org.springaicommunity.mcp.annotation.McpMeta;
import org.springaicommunity.mcp.annotation.McpTool;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证票务 MCP 工具注册范围和用户身份传递方式。
 */
class TicketQueryToolsTests {

    /**
     * 验证九个只读工具和一个受保护购票工具都由 MCP 服务提供。
     */
    @Test
    void registersOnlyExpectedReadOnlyTools() {
        // 从工具方法注解读取对外名称，防止后续未受保护的写工具被意外注册。
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
                "list_my_orders",
                "get_my_order_detail",
                "preview_order_cancellation",
                "preview_ticket_refund",
                "query_pay_status",
                "execute_confirmed_ticket_purchase");
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
                "request-a", "user-a", "alice", "conversation-a", "turn-a", "topic-a", "", "");
        PassengerView passenger = new PassengerView("passenger-a", "张三", 0, "1***********1", 0, "138****0000", 1);
        when(authenticator.authenticate(meta)).thenReturn(identity);
        when(businessClient.listPassengers(identity)).thenReturn(List.of(passenger));
        TicketQueryTools tools = new TicketQueryTools(authenticator, businessClient, new ObjectMapper());

        // 工具公开方法只有 McpMeta 参数，用户标识由鉴权器返回。
        Method method = TicketQueryTools.class.getMethod("listMyPassengers", McpMeta.class);
        assertThat(method.getParameterTypes()).containsExactly(McpMeta.class);
        assertThat(tools.listMyPassengers(meta)).containsExactly(passenger);
        verify(businessClient).listPassengers(identity);
    }

    /**
     * 验证订单详情工具只使用签名身份查询当前用户订单。
     */
    @Test
    void orderDetailToolUsesAuthenticatedIdentity() {
        McpRequestAuthenticator authenticator = mock(McpRequestAuthenticator.class);
        TicketBusinessClient businessClient = mock(TicketBusinessClient.class);
        McpMeta meta = new McpMeta(java.util.Map.of());
        McpCallerIdentity identity = new McpCallerIdentity(
                "request-a", "user-a", "alice", "conversation-a", "turn-a", "topic-a", "", "");
        OrderDetailView order = new OrderDetailView(
                "order-1", "train-1", "G1", "北京南", "上海虹桥",
                "2026-07-20", "09:00", "13:30", 10,
                false, false, true, List.of());
        when(authenticator.authenticate(meta)).thenReturn(identity);
        when(businessClient.getOrderDetail("order-1", identity)).thenReturn(order);
        TicketQueryTools tools = new TicketQueryTools(authenticator, businessClient, new ObjectMapper());

        // 工具参数只包含订单号，用户归属由签名元数据和下游安全详情接口共同校验。
        assertThat(tools.getMyOrderDetail("order-1", meta)).isEqualTo(order);
        verify(businessClient).getOrderDetail("order-1", identity);
    }

    /**
     * 验证真实购票工具同时校验签名操作标识和不可变参数指纹。
     *
     * @throws Exception SHA-256 算法不可用时抛出
     */
    @Test
    void confirmedPurchaseRequiresMatchingSignedPayload() throws Exception {
        McpRequestAuthenticator authenticator = mock(McpRequestAuthenticator.class);
        TicketBusinessClient businessClient = mock(TicketBusinessClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        McpMeta meta = new McpMeta(java.util.Map.of());
        List<ConfirmedPurchasePassenger> passengers = List.of(new ConfirmedPurchasePassenger("passenger-1", 3));
        List<String> chooseSeats = List.of("01A");

        // 指纹使用与生产草案相同的字段顺序和 JSON 结构。
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("trainId", "train-100");
        payload.put("departure", "北京南");
        payload.put("arrival", "上海虹桥");
        payload.put("passengers", passengers);
        payload.put("chooseSeats", chooseSeats);
        String json = objectMapper.writeValueAsString(payload);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.getBytes(StandardCharsets.UTF_8)));
        McpCallerIdentity identity = new McpCallerIdentity(
                "request-a", "user-a", "alice", "conversation-a", "turn-a", "topic-a",
                "action-1", hash);
        ConfirmedPurchaseResult result = new ConfirmedPurchaseResult("order-1", List.of());
        when(authenticator.authenticate(meta)).thenReturn(identity);
        when(businessClient.purchase(
                "train-100", "北京南", "上海虹桥", passengers, chooseSeats, identity))
                .thenReturn(result);
        TicketQueryTools tools = new TicketQueryTools(authenticator, businessClient, objectMapper);

        // 完整匹配时执行下游购票，任一草案参数变化都会在业务调用前被拒绝。
        assertThat(tools.executeConfirmedPurchase(
                "action-1", "train-100", "北京南", "上海虹桥", passengers, chooseSeats, meta))
                .isEqualTo(result);
        assertThatThrownBy(() -> tools.executeConfirmedPurchase(
                "action-1", "train-100", "北京南", "杭州东", passengers, chooseSeats, meta))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirmed draft");
        verify(businessClient).purchase(
                "train-100", "北京南", "上海虹桥", passengers, chooseSeats, identity);
    }
}
